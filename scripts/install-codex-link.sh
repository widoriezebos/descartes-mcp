#!/usr/bin/env bash
# Install this debug skill into Codex via symlink (no duplication).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILL_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SKILL_NAME="debug"
CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"
REPLACE=false

usage() {
  cat <<'EOF'
Usage:
  .claude/skills/debug/scripts/install-codex-link.sh [options]

Options:
  --name <skill-name>      Destination folder name under $CODEX_HOME/skills (default: debug)
  --codex-home <path>      Override CODEX_HOME (default: $CODEX_HOME or ~/.codex)
  --replace                Replace an existing symlink at destination
  -h, --help               Show this help

Behavior:
  - Creates a symlink: $CODEX_HOME/skills/<name> -> <repo>/.claude/skills/debug
  - Fails if destination exists and is not the same symlink
  - Does not copy files, so updates in this repo are reflected immediately
EOF
}

fail() {
  echo "install-codex-link: $*" >&2
  exit 1
}

canonical_dir() {
  local dir="$1"
  if [[ -d "$dir" ]]; then
    (cd "$dir" && pwd -P)
  else
    return 1
  fi
}

resolve_symlink_target() {
  local link_path="$1"
  local link_dir raw_target full_target
  link_dir="$(dirname "$link_path")"
  raw_target="$(readlink "$link_path")"
  if [[ "$raw_target" == /* ]]; then
    full_target="$raw_target"
  else
    full_target="${link_dir}/${raw_target}"
  fi
  canonical_dir "$full_target"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)
      [[ -n "${2:-}" ]] || fail "missing value for --name"
      SKILL_NAME="$2"
      shift 2
      ;;
    --codex-home)
      [[ -n "${2:-}" ]] || fail "missing value for --codex-home"
      CODEX_HOME="$2"
      shift 2
      ;;
    --replace)
      REPLACE=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1 (use --help)"
      ;;
  esac
done

[[ -f "${SKILL_DIR}/SKILL.md" ]] || fail "SKILL.md not found in ${SKILL_DIR}"

DEST_ROOT="${CODEX_HOME}/skills"
DEST_LINK="${DEST_ROOT}/${SKILL_NAME}"
mkdir -p "$DEST_ROOT"

if [[ -L "$DEST_LINK" ]]; then
  current_target="$(resolve_symlink_target "$DEST_LINK" || true)"
  expected_target="$(canonical_dir "$SKILL_DIR")"
  if [[ -n "$current_target" && "$current_target" == "$expected_target" ]]; then
    echo "Codex skill link already installed:"
    echo "  ${DEST_LINK} -> ${expected_target}"
    echo "Restart Codex to pick up new skills."
    exit 0
  fi

  if [[ "$REPLACE" != "true" ]]; then
    fail "destination symlink exists at ${DEST_LINK}; use --replace to overwrite"
  fi
  rm "$DEST_LINK"
elif [[ -e "$DEST_LINK" ]]; then
  fail "destination exists and is not a symlink: ${DEST_LINK}"
fi

ln -s "$SKILL_DIR" "$DEST_LINK"

echo "Installed Codex skill link:"
echo "  ${DEST_LINK} -> ${SKILL_DIR}"
echo "No files copied; this link always reflects latest repo changes."
echo "Restart Codex to pick up new skills."
