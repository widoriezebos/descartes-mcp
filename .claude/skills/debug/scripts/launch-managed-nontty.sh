#!/usr/bin/env bash
# Wrapper to avoid script drift: delegate to canonical repo launcher.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_SCRIPT="${SCRIPT_DIR}/../../../../scripts/launch-managed-nontty.sh"
PWD_SCRIPT="$(pwd)/scripts/launch-managed-nontty.sh"
CANONICAL_SCRIPT="${DESCARTES_LAUNCH_SCRIPT:-$DEFAULT_SCRIPT}"

if [[ ! -f "${CANONICAL_SCRIPT}" ]]; then
  if [[ -f "${PWD_SCRIPT}" ]]; then
    CANONICAL_SCRIPT="${PWD_SCRIPT}"
  fi
fi

if [[ ! -f "${CANONICAL_SCRIPT}" ]]; then
  cat >&2 <<EOF
launch-managed-nontty: canonical script not found:
  requested: ${CANONICAL_SCRIPT}
  default:   ${DEFAULT_SCRIPT}
  cwd path:  ${PWD_SCRIPT}

This skill wrapper expects the repo launcher at:
  scripts/launch-managed-nontty.sh

Next steps:
  1. Copy the canonical launcher into your repository:
       cp /path/to/descartes-mcp/scripts/launch-managed-nontty.sh ./scripts/
  2. Or point this wrapper at a custom location:
       export DESCARTES_LAUNCH_SCRIPT=/absolute/path/launch-managed-nontty.sh
  3. Or replace this wrapper with the full launcher implementation.
  4. Validate setup:
       .claude/skills/debug/scripts/preflight.sh
  5. Re-run:
       .claude/skills/debug/scripts/launch-managed-nontty.sh --help
EOF
  exit 1
fi

exec bash "${CANONICAL_SCRIPT}" "$@"
