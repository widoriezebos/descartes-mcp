#!/usr/bin/env bash
# Validate Descartes debug-skill launcher dependencies after install/copy.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_SCRIPT="${SCRIPT_DIR}/../../../../scripts/launch-managed-nontty.sh"
PWD_SCRIPT="$(pwd)/scripts/launch-managed-nontty.sh"
SELECTED_SCRIPT="${DESCARTES_LAUNCH_SCRIPT:-$DEFAULT_SCRIPT}"

if [[ ! -f "${SELECTED_SCRIPT}" && -f "${PWD_SCRIPT}" ]]; then
  SELECTED_SCRIPT="${PWD_SCRIPT}"
fi

if [[ ! -f "${SELECTED_SCRIPT}" ]]; then
  cat >&2 <<EOF
descartes-debug preflight failed: launch script not found.
  requested: ${DESCARTES_LAUNCH_SCRIPT:-<not set>}
  default:   ${DEFAULT_SCRIPT}
  cwd path:  ${PWD_SCRIPT}

Fix one of:
  1. Copy the canonical launcher into your repository:
       cp /path/to/descartes-mcp/scripts/launch-managed-nontty.sh ./scripts/
  2. Or point to a custom launcher:
       export DESCARTES_LAUNCH_SCRIPT=/absolute/path/launch-managed-nontty.sh

Then re-run:
  .agents/skills/descartes-debug/scripts/preflight.sh
EOF
  exit 1
fi

if ! bash "${SELECTED_SCRIPT}" --help >/dev/null 2>&1; then
  cat >&2 <<EOF
descartes-debug preflight failed: launcher exists but did not execute cleanly.
  launcher: ${SELECTED_SCRIPT}

Verify the script is readable and valid, then re-run:
  .agents/skills/descartes-debug/scripts/preflight.sh
EOF
  exit 1
fi

echo "descartes-debug preflight: OK"
echo "  launcher: ${SELECTED_SCRIPT}"
