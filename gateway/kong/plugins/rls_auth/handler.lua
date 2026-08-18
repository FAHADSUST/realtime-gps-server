-- rls_auth: the Realtime Location Service authentication plugin.
--
-- Every protected route runs this. It asks the Id service whether the presented token is valid and,
-- if so, puts the verified identity on the upstream request. Services downstream trust
-- X-Company-Id / X-User-Id precisely because this plugin is the only thing that can set them.
--
-- Ordering: PRIORITY is deliberately below request-transformer (801). That plugin strips
-- client-supplied identity headers, and it must run *first* - otherwise this plugin would set the
-- verified identity and request-transformer would promptly remove it again.

local http = require "resty.http"
local cjson = require "cjson.safe"

local kong = kong
local ngx = ngx

local RlsAuth = {
  PRIORITY = 800,
  VERSION = "1.0.0",
}

local COMPANY_HEADER = "X-Company-Id"
local USER_HEADER = "X-User-Id"
local APP_KEY_HEADER = "X-App-Key"
local CORRELATION_HEADER = "X-Correlation-Id"
local EXPIRES_IN_HEADER = "X-Token-Expires-In"

local function problem(status, code, detail)
  return cjson.encode({
    type = "https://docs.rls.gps/errors/" .. code,
    title = detail,
    status = status,
    detail = detail,
    code = code,
    timestamp = os.date("!%Y-%m-%dT%H:%M:%SZ"),
  })
end

local function exit(status, body)
  return kong.response.exit(status, body, { ["Content-Type"] = "application/problem+json" })
end

--- Asks the Id service about this token.
--
-- Returns `decision, nil, ttl` - the third value tells Kong's cache how long to keep it:
--   * authorised    -> the token's own remaining life, capped by max_cache_ttl
--   * not authorised-> negative_cache_ttl, so a replayed bad token stops costing a round trip
-- Returning `nil, err` means "could not decide", and Kong's cache stores nothing: an outage must
-- never be remembered as a verdict.
local function load_decision(conf, authorization, correlation_id)
  local client = http.new()
  client:set_timeouts(conf.connect_timeout, conf.send_timeout, conf.read_timeout)

  local headers = { ["Authorization"] = authorization }
  if correlation_id then
    headers[CORRELATION_HEADER] = correlation_id
  end

  local res, err = client:request_uri(conf.authenticate_url, {
    method = "GET",
    headers = headers,
    keepalive_timeout = 60000,
    keepalive_pool = 20,
  })

  if not res then
    return nil, err or "no response"
  end

  if res.status ~= 200 then
    if res.status >= 500 then
      -- The Id service is broken, not the caller's token. Do not cache, do not call it a 401.
      return nil, "id service returned " .. res.status
    end
    -- The Id service already produced an RFC 7807 body with a stable code; pass it through
    -- unchanged so the client sees exactly why, rather than a gateway-flavoured guess.
    return {
      authorized = false,
      status = res.status,
      body = res.body,
    }, nil, conf.negative_cache_ttl
  end

  local company_id = res.headers[COMPANY_HEADER]
  local user_id = res.headers[USER_HEADER]
  if not company_id or not user_id then
    -- A 200 without an identity means the Id service is not what we think it is. Failing closed
    -- here matters: the alternative is forwarding a request with no identity at all.
    return nil, "authenticate response did not carry an identity"
  end

  -- Never outlive the token itself: a cached decision must not keep a revoked user working.
  local expires_in = tonumber(res.headers[EXPIRES_IN_HEADER]) or 0
  local ttl = math.min(expires_in, conf.max_cache_ttl)
  if ttl < 1 then
    ttl = 1
  end

  return {
    authorized = true,
    company_id = company_id,
    user_id = user_id,
    app_key = res.headers[APP_KEY_HEADER],
  }, nil, ttl
end

function RlsAuth:access(conf)
  local authorization = kong.request.get_header("authorization")
  if not authorization or authorization == "" then
    return exit(401, problem(401, "token_missing", "No access token was presented"))
  end

  -- Hashed so the raw token is not sitting in a cache key. md5 is a key, not a security control -
  -- the token is already a bearer secret and the cache is per-node memory.
  local cache_key = "rls_auth:" .. ngx.md5(authorization)

  local decision, err = kong.cache:get(cache_key, nil, load_decision, conf, authorization,
    kong.request.get_header(CORRELATION_HEADER))

  if not decision then
    kong.log.err("rls_auth: could not authenticate: ", err)
    return exit(503, problem(503, "authentication_unavailable",
      "Authentication is temporarily unavailable"))
  end

  if not decision.authorized then
    return kong.response.exit(decision.status, decision.body,
      { ["Content-Type"] = "application/problem+json" })
  end

  kong.service.request.set_header(COMPANY_HEADER, decision.company_id)
  kong.service.request.set_header(USER_HEADER, decision.user_id)
  if decision.app_key then
    kong.service.request.set_header(APP_KEY_HEADER, decision.app_key)
  end
end

return RlsAuth
