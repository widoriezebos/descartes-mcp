# Debug Skill Setup (Claude Code + Codex CLI)

This repository ships a debugger skill at:

`./.claude/skills/debug`

Use this guide to enable it locally and copy it to other projects without maintaining duplicate implementations.

## What Is Included

- `SKILL.md`: debugger workflow and runtime-accurate guidance.
- `scripts/preflight.sh`: validates launcher dependencies after copy/install.
- `scripts/install-codex-link.sh`: installs a no-duplication symlink into Codex skills.
- `scripts/launch-managed-nontty.sh`: thin wrapper to canonical launcher.

## In This Repository

### Claude Code

Claude Code reads repo-local skills from `.claude/skills/`, so this skill is available directly from the repo checkout.

### Codex CLI

Codex CLI discovers skills from `$CODEX_HOME/skills` (default `~/.codex/skills`).
Install a symlink to avoid copying:

```bash
.claude/skills/debug/scripts/install-codex-link.sh
```

Then restart Codex CLI.

## Copy to Another Project

From the destination project root:

```bash
mkdir -p .claude/skills
cp -R /path/to/descartes-mcp/.claude/skills/debug ./.claude/skills/
```

The copied skill expects a launcher at `./scripts/launch-managed-nontty.sh`.
Provide it by copying the canonical script:

```bash
mkdir -p scripts
cp /path/to/descartes-mcp/scripts/launch-managed-nontty.sh ./scripts/
```

Or point to a custom launcher:

```bash
export DESCARTES_LAUNCH_SCRIPT=/absolute/path/launch-managed-nontty.sh
```

Validate the setup:

```bash
.claude/skills/debug/scripts/preflight.sh
```

## Codex CLI Options in Other Projects

### Option A: Use the installer script (recommended)

```bash
.claude/skills/debug/scripts/install-codex-link.sh
```

### Option B: Install under a custom name (avoid collisions)

```bash
.claude/skills/debug/scripts/install-codex-link.sh --name descartes-debug
```

### Option C: Manual symlink

```bash
mkdir -p "${CODEX_HOME:-$HOME/.codex}/skills"
ln -s "$(pwd)/.claude/skills/debug" "${CODEX_HOME:-$HOME/.codex}/skills/debug"
```

If the destination already exists:

- Existing symlink: run installer with `--replace`.
- Existing directory: choose another name via `--name` or remove the directory first.

Restart Codex CLI after linking.

## Rename the Skill Folder

You can rename the repo-local folder and still use the skill:

```bash
mv .claude/skills/debug .claude/skills/descartes-debug
.claude/skills/descartes-debug/scripts/install-codex-link.sh --name descartes-debug
```

When renamed, keep internal file structure unchanged (`SKILL.md`, `scripts/`, `references/`).
