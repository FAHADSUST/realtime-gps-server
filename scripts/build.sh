#!/usr/bin/env bash
# Build the whole reactor with JDK 21, regardless of the JDK Maven defaults to.
#
#   ./scripts/build.sh                 -> mvn verify   (unit + integration tests)
#   ./scripts/build.sh -DskipTests package
#   ./scripts/build.sh -pl services/id-service -am test
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Override with GPS_JAVA_HOME if your JDK 21 lives elsewhere.
JDK21="${GPS_JAVA_HOME:-C:/Program Files/Java/jdk-21}"

if [ ! -x "${JDK21}/bin/javac" ] && [ ! -f "${JDK21}/bin/javac.exe" ]; then
  echo "ERROR: JDK 21 not found at '${JDK21}'." >&2
  echo "       Set GPS_JAVA_HOME to your JDK 21 installation and re-run." >&2
  exit 1
fi

export JAVA_HOME="${JDK21}"
echo "==> JAVA_HOME=${JAVA_HOME}"

if [ "$#" -eq 0 ]; then
  set -- verify
fi

cd "${ROOT_DIR}"
exec mvn "$@"
