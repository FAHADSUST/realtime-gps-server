#!/usr/bin/env bash
# Builds the GPS images and pushes them to the cluster's registry, tagged with the Git commit.
#
#   ./scripts/publish-images.sh            # tag = the current commit (12-character SHA)
#   ./scripts/publish-images.sh 1.4.0      # an explicit tag
#
# Needs Docker with BuildKit, JDK 21 (see build.sh) and a registry login:
#   docker login registry.rls.internal -u gps
# REGISTRY points elsewhere; PLATFORM builds for another node architecture (default linux/amd64).
#
# Releasing is then a Git change: set apps.gps.imageTag in deploy/kubernetes/gitops/values.yaml to
# the tag and merge. Argo CD rolls the services out.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REGISTRY="${REGISTRY:-registry.rls.internal}"
PLATFORM="${PLATFORM:-linux/amd64}"
SERVICES=(id-service ping-service history-service)

if [ "$#" -gt 0 ]; then
  TAG="$1"
else
  # A commit tag must name exactly one build: uncommitted changes would ship under its name.
  if [ -n "$(git -C "${ROOT_DIR}" status --porcelain)" ]; then
    echo "ERROR: uncommitted changes. Commit them, or pass an explicit tag." >&2
    exit 1
  fi
  TAG="$(git -C "${ROOT_DIR}" rev-parse --short=12 HEAD)"
fi

case "${TAG}" in
  '' | *[!A-Za-z0-9._-]*)
    echo "ERROR: '${TAG}' is not a valid image tag (letters, digits, '.', '_', '-')." >&2
    exit 1
    ;;
esac

echo "==> Building and unit-testing the services"
"${ROOT_DIR}/scripts/build.sh" -q package

images=()
build() {
  local image="$1" context="$2"
  echo "==> ${image}"
  docker build --pull --platform "${PLATFORM}" -t "${image}" "${context}"
  images+=("${image}")
}

for service in "${SERVICES[@]}"; do
  build "${REGISTRY}/gps/${service}:${TAG}" "${ROOT_DIR}/services/${service}"
done
build "${REGISTRY}/gps/kong:${TAG}" "${ROOT_DIR}/gateway/kong"

for image in "${images[@]}"; do
  docker push "${image}"
done

echo
echo "Pushed ${#images[@]} images tagged ${TAG} to ${REGISTRY}."
echo "Release them: set apps.gps.imageTag to \"${TAG}\" in deploy/kubernetes/gitops/values.yaml, then merge."
