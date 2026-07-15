# Cross-Agent Debug Skill

Descartes ships one physical debugger skill that follows the open Agent Skills format:

```text
.agents/skills/descartes-debug/
├── SKILL.md
├── agents/openai.yaml
├── references/
└── scripts/

.claude/skills/descartes-debug -> ../../.agents/skills/descartes-debug
```

Do not create a second copy under a client-specific directory. The checked-in Claude entry is a relative directory symlink to the canonical tree.

## Client Discovery

| Client | Discovery path | Repository setup |
| --- | --- | --- |
| Codex | `.agents/skills/descartes-debug` | Direct discovery |
| Gemini CLI | `.agents/skills/descartes-debug` | Direct discovery through Gemini's `.agents/skills` alias |
| Claude Code 2.1.203+ | `.claude/skills/descartes-debug` | Follows the checked-in symlink to the canonical tree |

The specific name `descartes-debug` avoids overriding Claude Code's bundled `/debug` skill.

`SKILL.md` contains only portable `name` and `description` frontmatter. Codex presentation metadata lives in `agents/openai.yaml`; other clients ignore that optional file and use the same workflow instructions and resources.

## Repository Configuration

Each client still needs its native MCP connection format. These checked-in files all launch `config/mcp/mcp-tcp-adapter.js` against the proxy on port `9090`:

| Client | MCP configuration |
| --- | --- |
| Claude Code | `.mcp.json`, including its per-server client deadline |
| Codex | `.codex/config.toml` |
| Gemini CLI | `.gemini/settings.json` |

Run the proxy before starting a client:

```bash
./scripts/run-remote-proxy-from-maven.sh
```

## Validate This Checkout

```bash
.agents/skills/descartes-debug/scripts/preflight.sh
```

The preflight verifies that the skill can reach the canonical non-TTY launcher. Repository tests additionally verify the symlink, portable frontmatter, Codex metadata, MCP timeout alignment, and release-version references.

## Copy to Another Project

Copy only the canonical skill, then add the Claude discovery link:

```bash
mkdir -p .agents/skills .claude/skills
cp -R /path/to/descartes-mcp/.agents/skills/descartes-debug .agents/skills/
ln -s ../../.agents/skills/descartes-debug .claude/skills/descartes-debug
```

Codex and Gemini need no additional skill installation. Claude Code follows the symlink. On Windows, enable Git symlink support or create an equivalent directory junction; do not maintain a copied second tree.

The skill expects `scripts/launch-managed-nontty.sh` in the destination project. Either copy it:

```bash
mkdir -p scripts
cp /path/to/descartes-mcp/scripts/launch-managed-nontty.sh scripts/
```

Or point the skill wrapper at an existing launcher:

```bash
export DESCARTES_LAUNCH_SCRIPT=/absolute/path/launch-managed-nontty.sh
```

Then run `.agents/skills/descartes-debug/scripts/preflight.sh` in the destination project.

## Remove the Legacy Codex Link

Older checkouts installed `~/.codex/skills/debug` as a user-level link. It is obsolete because Codex now discovers the repository's `.agents/skills` directly, and leaving it installed can expose duplicate skills.

Inspect it before removing it:

```bash
ls -l "${CODEX_HOME:-$HOME/.codex}/skills/debug"
unlink "${CODEX_HOME:-$HOME/.codex}/skills/debug"
```

Only run `unlink` when that path is the old symlink; do not remove a real directory.

## Standards and Client Documentation

- [Agent Skills specification](https://agentskills.io/specification)
- [Codex skill discovery and metadata](https://learn.chatgpt.com/docs/build-skills.md)
- [Claude Code skills and symlink discovery](https://code.claude.com/docs/en/skills)
- [Gemini CLI Agent Skills](https://geminicli.com/docs/cli/using-agent-skills/)
