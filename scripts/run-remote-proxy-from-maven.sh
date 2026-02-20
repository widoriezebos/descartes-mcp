#!/usr/bin/env bash
#
# Pull the proxy shaded artifact from Maven repositories and launch it.
#
# Usage:
#   scripts/run-remote-proxy-from-maven.sh --version 1.0.0
#   scripts/run-remote-proxy-from-maven.sh --version 1.0.0 --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
#
# Env overrides:
#   DESCARTES_PROXY_GROUP_ID      (default: com.bitsapplied.descartes)
#   DESCARTES_PROXY_ARTIFACT_ID   (default: descartes-mcp)
#   DESCARTES_PROXY_VERSION       (required if --version is not set)
#   DESCARTES_PROXY_CLASSIFIER    (default: proxy)
#

set -euo pipefail

GROUP_ID="${DESCARTES_PROXY_GROUP_ID:-com.bitsapplied.descartes}"
ARTIFACT_ID="${DESCARTES_PROXY_ARTIFACT_ID:-descartes-mcp}"
VERSION="${DESCARTES_PROXY_VERSION:-}"
CLASSIFIER="${DESCARTES_PROXY_CLASSIFIER:-proxy}"
TYPE="jar"
ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --group-id)
            GROUP_ID="$2"
            shift 2
            ;;
        --artifact-id)
            ARTIFACT_ID="$2"
            shift 2
            ;;
        --version)
            VERSION="$2"
            shift 2
            ;;
        --classifier)
            CLASSIFIER="$2"
            shift 2
            ;;
        -h|--help)
            cat <<'EOF'
Usage:
  scripts/run-remote-proxy-from-maven.sh --version <version> [proxy-args...]

Examples:
  scripts/run-remote-proxy-from-maven.sh --version 1.0.0
  scripts/run-remote-proxy-from-maven.sh --version 1.0.0 --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
EOF
            exit 0
            ;;
        --)
            shift
            ARGS+=("$@")
            break
            ;;
        *)
            ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "Missing version. Provide --version <version> or DESCARTES_PROXY_VERSION." >&2
    exit 1
fi

if [[ ${#ARGS[@]} -eq 0 ]]; then
    ARGS=(--jdwp-host localhost --jdwp-port 5005 --mcp-port 9090)
fi

COORDINATE="${GROUP_ID}:${ARTIFACT_ID}:${VERSION}:${TYPE}:${CLASSIFIER}"
echo "Fetching ${COORDINATE} ..."

mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get "-Dartifact=${COORDINATE}"

LOCAL_REPO="$(
    mvn -q -DforceStdout -Dexpression=settings.localRepository help:evaluate \
        | sed '/^\[/d' \
        | tail -n1
)"

GROUP_PATH="${GROUP_ID//.//}"
JAR_PATH="${LOCAL_REPO}/${GROUP_PATH}/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}-${CLASSIFIER}.jar"

if [[ ! -f "$JAR_PATH" ]]; then
    echo "Downloaded artifact not found at: $JAR_PATH" >&2
    exit 1
fi

echo "Starting proxy from: $JAR_PATH"
echo "Args: ${ARGS[*]}"

exec java \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -jar "$JAR_PATH" \
    "${ARGS[@]}"
