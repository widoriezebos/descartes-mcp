# Agent Operations Handbook (Doc Pointer)

Canonical source: `AGENTS.md` at the repository root.

This file is intentionally short. If this file and root `AGENTS.md` ever differ, root `AGENTS.md` is authoritative.

## Why This Exists

- Some readers start in `doc/` and may miss root instruction files.
- Keeping this page minimal avoids drift and duplicated maintenance.

## Read Next

- `AGENTS.md` for build/test/run commands, coding standards, and repo workflow rules.
- `doc/agent-template.md` for repo-agnostic copy/paste templates.
- `doc/debugger.md` for debugger workflows and timeout semantics.
- `doc/adapter.md` for TCP adapter behavior and environment variables.

## Critical Reminders

- Launch remote JDWP targets with `scripts/launch-managed-nontty.sh --name <name> -- <command>`.
- Use non-TTY mode for tool-based launches (`tty=false`).
- Before Maven test runs, clear stale surefire forks:
  `pkill -9 -f surefirebooter 2>/dev/null`.
