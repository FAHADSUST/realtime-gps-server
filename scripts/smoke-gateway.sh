#!/usr/bin/env bash
# End-to-end check of the gateway's auth path against a running stack.
#
#   ./scripts/up.sh && ./scripts/smoke-gateway.sh
#
# Exits non-zero on the first failed expectation, so it is usable as a gate in CI.
set -uo pipefail

KONG="${KONG_URL:-http://localhost:8000}"
ID_INTERNAL="${ID_INTERNAL_URL:-http://localhost:9081}"
SRET="${SERVER_SECRET:-local-dev-server-secret}"

RUN_ID="$$"
COMPANY_NAME="Smoke Test ${RUN_ID}"
USERNAME="smoke-${RUN_ID}"
PASSWORD="smoke-test-password"

failures=0
BODY_FILE="$(mktemp)"
trap 'rm -f "${BODY_FILE}"' EXIT

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }

# Runs a request, leaves the body in $BODY_FILE, echoes the status code.
request() {
  curl -s -o "${BODY_FILE}" -w '%{http_code}' "$@"
}

expect_status() {
  local label="$1" expected="$2" actual="$3"
  if [ "${actual}" = "${expected}" ]; then
    pass "${label}"
  else
    fail "${label} - expected ${expected}, got ${actual}: $(head -c 200 "${BODY_FILE}")"
  fi
}

expect_body_field() {
  local label="$1" field="$2" expected="$3"
  local actual
  actual="$(json_field "${field}" < "${BODY_FILE}")"
  if [ "${actual}" = "${expected}" ]; then
    pass "${label}"
  else
    fail "${label} - expected ${field}='${expected}', got '${actual}'"
  fi
}

json_field() {
  sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1
}

echo "==> Gateway smoke test against ${KONG}"

# ---------------------------------------------------------------- setup ----
status="$(request -X POST "${ID_INTERNAL}/api/v1/company/signup" \
  -H "sret: ${SRET}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"${COMPANY_NAME}\"}")"
if [ "${status}" != "201" ]; then
  echo "ERROR: could not register a company (${status}): $(cat "${BODY_FILE}")" >&2
  echo "       Is the stack up? ./scripts/up.sh" >&2
  exit 1
fi
APP_KEY="$(json_field appKey < "${BODY_FILE}")"
APP_SECRET="$(json_field appSecret < "${BODY_FILE}")"
COMPANY_ID="$(json_field companyId < "${BODY_FILE}")"
echo "    registered company ${COMPANY_ID}"

# ------------------------------------------------------ through the gateway ----
status="$(request -X POST "${KONG}/api/v1/user/signup" -H 'Content-Type: application/json' \
  -d "{\"appKey\":\"${APP_KEY}\",\"appSecret\":\"${APP_SECRET}\",\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")"
expect_status "user signup is public through the gateway" 201 "${status}"
USER_ID="$(json_field userId < "${BODY_FILE}")"

status="$(request -X POST "${KONG}/api/v1/auth/token" -H 'Content-Type: application/json' \
  -d "{\"appKey\":\"${APP_KEY}\",\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}")"
expect_status "token issuance is public through the gateway" 200 "${status}"
TOKEN="$(json_field accessToken < "${BODY_FILE}")"

# ------------------------------------------------------------ the auth path ----
status="$(request "${KONG}/api/v1/user/resolve" -H "Authorization: Bearer ${TOKEN}")"
expect_status "a valid token reaches the service" 200 "${status}"

status="$(request "${KONG}/api/v1/user/resolve")"
expect_status "no token is refused" 401 "${status}"
expect_body_field "  ...with code token_missing" code token_missing

status="$(request "${KONG}/api/v1/user/resolve" -H 'Authorization: Bearer not-a-real-token')"
expect_status "a garbage token is refused" 401 "${status}"
expect_body_field "  ...with the Id service's own code" code token_invalid

# The whole point of the trust boundary: identity headers from a client are worthless.
status="$(request "${KONG}/api/v1/user/resolve" \
  -H 'X-Company-Id: forged-company' -H 'X-User-Id: forged-user')"
expect_status "forged identity headers alone do not authenticate" 401 "${status}"

# ...and they are overwritten, not merely ignored, when a real token is present.
status="$(request "${KONG}/api/v1/user/resolve" -H "Authorization: Bearer ${TOKEN}" \
  -H 'X-Company-Id: forged-company' -H 'X-User-Id: forged-user')"
expect_status "a forged identity alongside a valid token is overwritten" 200 "${status}"
if grep -q "\"companyId\":\"${COMPANY_ID}\"" "${BODY_FILE}"; then
  pass "  ...the response is scoped to the real company"
else
  fail "  ...the response is scoped to the real company - body: $(head -c 200 "${BODY_FILE}")"
fi

# ------------------------------------------------------ restricted endpoints ----
status="$(request -X POST "${KONG}/api/v1/company/signup" -H "sret: ${SRET}" \
  -H 'Content-Type: application/json' -d '{"name":"Sneaky Inc"}')"
expect_status "company signup is not routable through the gateway" 404 "${status}"

status="$(request "${KONG}/api/v1/internal/authenticate" -H "Authorization: Bearer ${TOKEN}")"
expect_status "internal authenticate is not routable through the gateway" 404 "${status}"

# ------------------------------------------------------------- rate limiting ----
echo "    hammering the token endpoint (expects 429 within 15 attempts)"
limited=0
for _ in $(seq 1 15); do
  status="$(request -X POST "${KONG}/api/v1/auth/token" -H 'Content-Type: application/json' \
    -d "{\"appKey\":\"${APP_KEY}\",\"username\":\"${USERNAME}\",\"password\":\"wrong-password\"}")"
  if [ "${status}" = "429" ]; then
    limited=1
    break
  fi
done
if [ "${limited}" = "1" ]; then
  pass "brute force against token issuance is rate limited"
else
  fail "brute force against token issuance was NOT rate limited"
fi

echo
if [ "${failures}" -eq 0 ]; then
  echo "All gateway checks passed."
else
  echo "${failures} check(s) failed." >&2
fi
exit "${failures}"
