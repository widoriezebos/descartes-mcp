#!/usr/bin/env bash
#
# Pull the proxy shaded artifact from Maven repositories and launch it.
#
# Usage:
#   scripts/run-remote-proxy-from-maven.sh --version 1.0.3
#   scripts/run-remote-proxy-from-maven.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
#
# Env overrides:
#   DESCARTES_PROXY_GROUP_ID                   (default: com.bitsapplied.descartes)
#   DESCARTES_PROXY_ARTIFACT_ID                (default: descartes-mcp)
#   DESCARTES_PROXY_VERSION                    (optional, inferred from local pom.xml when omitted)
#   DESCARTES_PROXY_CLASSIFIER                 (default: proxy)
#   DESCARTES_PROXY_LOG_FILE                   (optional)
#   DESCARTES_PROXY_ALLOW_LOCAL_BUILD_FALLBACK (default: 1)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if PROJECT_ROOT_CANDIDATE="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)"; then
    PROJECT_ROOT="$PROJECT_ROOT_CANDIDATE"
else
    PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi

GROUP_ID="${DESCARTES_PROXY_GROUP_ID:-com.bitsapplied.descartes}"
ARTIFACT_ID="${DESCARTES_PROXY_ARTIFACT_ID:-descartes-mcp}"
VERSION="${DESCARTES_PROXY_VERSION:-}"
CLASSIFIER="${DESCARTES_PROXY_CLASSIFIER:-proxy}"
TYPE="jar"
LOG_FILE="${DESCARTES_PROXY_LOG_FILE:-}"
ALLOW_LOCAL_BUILD_FALLBACK="${DESCARTES_PROXY_ALLOW_LOCAL_BUILD_FALLBACK:-1}"
ARGS=()

usage() {
    cat <<'EOF'
Usage:
  scripts/run-remote-proxy-from-maven.sh [wrapper-options] [proxy-args...]

Wrapper options:
  --group-id <groupId>         Override artifact groupId
  --artifact-id <artifactId>   Override artifactId
  --version <version>          Artifact version (optional when local pom.xml is available)
  --classifier <classifier>    Artifact classifier (default: proxy)
  --log-file <path>            Mirror output to file via tee
  --no-local-build-fallback    Disable local source fallback when artifact fetch fails
  -h, --help                   Show this help

Examples:
  scripts/run-remote-proxy-from-maven.sh --version 1.0.3
  scripts/run-remote-proxy-from-maven.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
  scripts/run-remote-proxy-from-maven.sh --version 1.0.3 --log-file logs/descartes-proxy.log --auto-discover
EOF
}

require_value() {
    local option="$1"
    if [[ $# -lt 2 ]]; then
        echo "Missing value for ${option}" >&2
        exit 1
    fi
}

command -v mvn >/dev/null 2>&1 || {
    echo "mvn not found in PATH." >&2
    exit 1
}
command -v java >/dev/null 2>&1 || {
    echo "java not found in PATH." >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --group-id)
            require_value "$1" "$@"
            GROUP_ID="$2"
            shift 2
            ;;
        --artifact-id)
            require_value "$1" "$@"
            ARTIFACT_ID="$2"
            shift 2
            ;;
        --version)
            require_value "$1" "$@"
            VERSION="$2"
            shift 2
            ;;
        --classifier)
            require_value "$1" "$@"
            CLASSIFIER="$2"
            shift 2
            ;;
        --log-file)
            require_value "$1" "$@"
            LOG_FILE="$2"
            shift 2
            ;;
        --no-local-build-fallback)
            ALLOW_LOCAL_BUILD_FALLBACK="0"
            shift
            ;;
        -h|--help)
            usage
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

if [[ -z "$VERSION" && -f "$PROJECT_ROOT/pom.xml" ]]; then
    VERSION="$(
        mvn -q -f "$PROJECT_ROOT/pom.xml" -DforceStdout -Dexpression=project.version help:evaluate \
            | sed '/^\[/d' \
            | tail -n1
    )"
    if [[ -n "$VERSION" ]]; then
        echo "Using version from pom.xml: $VERSION"
    fi
fi

if [[ -z "$VERSION" ]]; then
    echo "Missing version. Provide --version <version>, set DESCARTES_PROXY_VERSION, or run from a repo containing pom.xml." >&2
    exit 1
fi

if [[ ${#ARGS[@]} -eq 0 ]]; then
    ARGS=(--jdwp-host localhost --jdwp-port 5005 --mcp-port 9090)
fi

if [[ -n "$LOG_FILE" ]]; then
    if [[ "$LOG_FILE" != /* ]]; then
        LOG_FILE="$PROJECT_ROOT/$LOG_FILE"
    fi
    mkdir -p "$(dirname "$LOG_FILE")"
    touch "$LOG_FILE"
    exec > >(tee -a "$LOG_FILE") 2>&1
    echo "Descartes proxy log file: $LOG_FILE"
fi

COORDINATE="${GROUP_ID}:${ARTIFACT_ID}:${VERSION}:${TYPE}:${CLASSIFIER}"
echo "Fetching ${COORDINATE} ..."

LOCAL_REPO="$(
    mvn -q -DforceStdout -Dexpression=settings.localRepository help:evaluate \
        | sed '/^\[/d' \
        | tail -n1
)"
GROUP_PATH="${GROUP_ID//.//}"
JAR_PATH="${LOCAL_REPO}/${GROUP_PATH}/${ARTIFACT_ID}/${VERSION}/${ARTIFACT_ID}-${VERSION}-${CLASSIFIER}.jar"

if ! mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get "-Dartifact=${COORDINATE}"; then
    if [[ "$ALLOW_LOCAL_BUILD_FALLBACK" == "1" && -f "$PROJECT_ROOT/pom.xml" ]]; then
        echo "Artifact fetch failed; falling back to local source build/install from $PROJECT_ROOT ..."
        mvn -q -f "$PROJECT_ROOT/pom.xml" -DskipTests install
    else
        echo "Failed to resolve ${COORDINATE}. Disable this fallback check with --no-local-build-fallback." >&2
        exit 1
    fi
fi

if [[ ! -f "$JAR_PATH" ]]; then
    if [[ "$ALLOW_LOCAL_BUILD_FALLBACK" == "1" && -f "$PROJECT_ROOT/pom.xml" ]]; then
        echo "Resolved artifact still missing, attempting one local install fallback ..."
        mvn -q -f "$PROJECT_ROOT/pom.xml" -DskipTests install
    fi
fi

if [[ ! -f "$JAR_PATH" ]]; then
    echo "Resolved artifact not found at: $JAR_PATH" >&2
    exit 1
fi

echo "Starting proxy from: $JAR_PATH"
echo "Args: ${ARGS[*]}"

exec java \
    --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
    -jar "$JAR_PATH" \
    "${ARGS[@]}"
