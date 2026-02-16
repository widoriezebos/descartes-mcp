---
name: debug
description: >-
  Debug Java applications using Descartes MCP debugger tools. Use when the user
  asks to debug, investigate, diagnose, or find bugs in Java code. Covers
  setting breakpoints, stepping through code, inspecting variables, evaluating
  expressions, analyzing threads, detecting deadlocks, and finding common Java
  bugs like NullPointerException, off-by-one errors, race conditions, and
  memory issues. Works in both embedded mode (full toolset) and remote proxy
  mode (debugger-only).
argument-hint: "[problem description or class/method to debug]"
---

# Descartes MCP Debugger Skill

You are debugging Java applications via JDWP (Java Debug Wire Protocol) using 11 Descartes MCP tools. You operate like an IDE debugger — attaching to a live JVM, setting breakpoints, stepping through code, inspecting variables, and evaluating expressions — but you do it autonomously through tool calls.

## Tool Reference

| Tool | Purpose | Key Operations |
|------|---------|----------------|
| `debugger_session` | Session lifecycle | `start`, `stop`, `status`, `threads`, `suspend`, `resume`, `resume_all` |
| `debugger_breakpoints` | Breakpoint management | `set`, `upsert`, `resolve_line`, `remove`, `remove_all`, `list`, `enable`, `disable` |
| `debugger_step` | Step execution | `step_over`, `step_into`, `step_out` |
| `debugger_threads` | Thread list/control | `list`, `inspect`, `suspend`, `resume`, `resume_all` |
| `debugger_stacktrace` | Stack frames | `capture`, `capture_filtered`, `get_frame`, `get_current_frame` |
| `debugger_variables` | Variable inspection | `get_variables`, `get_child_variables`, `get_static_fields` |
| `debugger_evaluate` | Expression evaluation | `evaluate` |
| `debugger_watch` | Watch expressions | `add`, `remove`, `remove_all`, `list`, `enable`, `disable`, `evaluate` |
| `debugger_events` | Event polling | `wait`, `fetch`, `clear` |
| `thread_analyzer` | Thread analysis | `thread_list`, `thread_inspect`, `thread_search`, `deadlocks`, `thread_dump` |
| `object_inspector` | Context object inspection | `inspect`, `fields`, `methods`, `type`, `value` |

## Runtime Reality Checks

- Breakpoint `condition` is accepted and stored, but current runtime behavior does **not** reliably enforce it as a hit filter. Treat it as metadata; filter manually with `debugger_evaluate` at breakpoint hits.
- `debugger.method_entry`, `debugger.method_exit`, and `debugger.exception` events require corresponding JDI event requests. Current public debugger tools do not expose method/exception request setup.
- `object_inspector` evaluates JShell expressions against shared `context`; it is not a replacement for suspended-frame inspection via `debugger_variables`/`debugger_evaluate`.

## Install Preflight (Run Once)

After copying/installing this skill, validate launcher dependencies:

```bash
.claude/skills/debug/scripts/preflight.sh
```

If `scripts/launch-managed-nontty.sh` is missing, preflight fails with exact remediation steps.

## Codex CLI Install (No Duplication)

To make this skill available to Codex CLI without copying files, install a symlink into `$CODEX_HOME/skills`:

```bash
.claude/skills/debug/scripts/install-codex-link.sh
```

Options:
- `--name <skill-name>` to choose destination folder name (default `debug`)
- `--codex-home <path>` to override `CODEX_HOME`
- `--replace` to replace an existing destination symlink

After linking, restart Codex so it picks up the new skill.

## Quick Start Loop (Use This First)

When the user asks to debug behavior drift, follow this generic loop before deep-diving:

1. Define two concrete statements: **expected state** and **observed state**
2. Build a **decision-gate map** from code/logs (branch points, policy gates, budget gates)
3. Set only 3-5 **high-signal breakpoints** on those gates
4. Reproduce once and capture a **Decision Snapshot** at each hit
5. Refine fast: if a breakpoint is noisy twice, move/disable it quickly
6. Validate the **causal chain** from first divergence to final symptom
7. Separate debugger-induced effects (pause/timeout) from product behavior
8. Report root cause as: line + branch/predicate value + runtime state + causal chain

## The Debugging Methodology

Follow these 10 steps for every debugging task:

### Step 1: Understand the Problem

Read the user's description and classify it:
- **Crash**: Exception, NPE, StackOverflow, ClassCast
- **Wrong result**: Incorrect output, off-by-one, logic error
- **Hang**: Deadlock, infinite loop, blocked thread
- **Performance**: Slow execution, high CPU, memory growth

Before tooling, write:
- **Expected state**: what should happen (one sentence)
- **Observed state**: what actually happened (one sentence)
- **First possible divergence**: where the two could start to differ

**Read the source code BEFORE setting any breakpoints.** Use the Read tool to examine the relevant class/method. Know which lines are executable, where methods begin and end, and what the expected behavior should be.

### Step 2: Detect Mode and Connect

Check session status first:
```
debugger_session(operation: "status")
```

If no active session, start one:
```
debugger_session(operation: "start", jdwp_timeout: 10000)
```

**Detect your operational mode** — this determines how you trigger workloads:
- **Embedded mode**: `jshell_async` is available. You have all tools (20+). You can trigger workloads programmatically.
- **Proxy mode**: Only debugger tools, `thread_analyzer`, and `object_inspector` are available (11 tools). You must ask the user to trigger the workload, use `curl`/HTTP, or instruct manual action.

**If `debugger_session start` fails with `JDWP_CONNECTION_FAILED`**, triage in order:
1. Is the target JVM running with `-agentlib:jdwp=...`? If not, see "Launching a Debug Target" below.
2. Are host and port correct? Embedded mode auto-detects; proxy mode configures JDWP host/port at proxy launch time (via `run-remote-proxy.sh --jdwp-host --jdwp-port`), not via `debugger_session start`.
3. Is the JDWP port reachable? Check firewall rules, bind scope (`address=*:5005` vs `address=5005`), and network path.
4. Is another debugger already attached? JDWP allows only one client at a time.

**Establish an event baseline** immediately after connecting. This drains stale breakpoint events from any previous session and gives you a sequence cursor for later waits:
```
debugger_events(operation: "fetch", types: ["debugger.breakpoint_hit"], max_events: 100)
```
Store `latest_sequence` from the response. You will pass it as `since_sequence` when waiting for events in Step 6.

### Step 3: Form a Hypothesis

Based on the problem classification and source code reading, hypothesize where the bug is:
- Which method is suspect?
- Which line(s) should you observe?
- What variable values would confirm or refute your hypothesis?

Use a lightweight hypothesis ledger:
```
H1: <expected relation> at <class:line> confirmed by <signal>
H2: ...
```
After each breakpoint hit, mark each hypothesis as `confirmed`, `refuted`, or `inconclusive`.

### Step 4: Set Strategic Breakpoints

Always preflight with `resolve_line` before setting breakpoints on uncertain lines:
```
debugger_breakpoints(operation: "resolve_line", class_name: "com.example.MyClass", line_number: 42)
```

Then set the breakpoint:
```
debugger_breakpoints(operation: "set", class_name: "com.example.MyClass", line_number: 42)
```

`condition` is currently best treated as metadata, not guaranteed hit filtering. Use manual gating at hit time:
```
debugger_breakpoints(operation: "set", class_name: "com.example.MyClass", line_number: 48, condition: "i >= 98")
```
After a hit on that breakpoint:
```
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "i >= 98")
```

Prioritize breakpoints by quality:
- **High-signal**: captures a branch/gate/policy decision directly
- **Medium-signal**: captures inputs that feed a later decision
- **Low-signal**: generic state with no clear decision impact

Rules:
- Start with high-signal breakpoints only
- Keep <= 5 active breakpoints unless investigating concurrency
- If a breakpoint is low-signal on 2 hits, disable/refine it immediately

See [references/breakpoint-strategy.md](references/breakpoint-strategy.md) for strategic placement guidance by bug type.

### Step 5: Trigger the Workload

**CRITICAL: Never use synchronous `jshell_repl` for code that will hit a breakpoint.** The breakpoint suspends the thread executing the MCP call, deadlocking the entire MCP channel.

**Embedded mode** — use `jshell_async`:
```
jshell_async(operation: "start", code: "new MyClass().myMethod()")
```
This returns immediately with a `task_id`.

**Proxy mode** — trigger externally:
- Ask the user: "Please trigger the operation now (e.g., send the HTTP request, click the button)"
- Use `curl` via Bash tool if the target exposes an HTTP endpoint
- The user exercises the application manually

### Step 6: Wait for Breakpoint Events

Wait for a breakpoint hit using your sequence cursor (established in Step 2, or carried forward from the previous iteration's `wait` response):
```
debugger_events(operation: "wait", types: ["debugger.breakpoint_hit"], since_sequence: <latest_sequence>, timeout_ms: 30000)
```

Update `latest_sequence` from the response for the next iteration.

If the wait times out, check: Is the breakpoint active? Was the workload triggered? Is this the correct class/line/path? If you set `condition`, remember it does not reliably gate hits.

Do not assume timeout root cause from a paused run. Track timeout attribution explicitly:
- `debugger-influenced`: long pauses/suspensions consumed available time budget
- `product-only`: reproduced without meaningful debug pauses

See [references/orchestration.md](references/orchestration.md) for the full async trigger + event polling pattern.

### Step 7: Inspect the Suspended State

Once a breakpoint hits, inspect in this order:

**1. Stack trace** — understand where you are:
```
debugger_stacktrace(operation: "capture_filtered", thread_id: <tid>, exclude_patterns: ["java.*", "javax.*", "jdk.*", "sun.*"])
```

**2. Variables** — see local state (progressive disclosure):
```
debugger_variables(operation: "get_variables", thread_id: <tid>, frame_index: 0)
```
Only expand objects you need:
```
debugger_variables(operation: "get_child_variables", thread_id: <tid>, variable_reference: <ref>)
```

**3. Evaluate expressions** — test your hypothesis:
```
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "list.size()")
```

Check multiple frames by varying `frame_index` to see caller context.

Capture a Decision Snapshot at each high-signal hit before deep object expansion:
- Runtime context: request/execution ID, thread ID/name, class/method/line
- Decision inputs: booleans/enums/reason strings used by the current branch
- Resource inputs: counters/sizes/budgets/time windows used by the decision
- Decision outcome: which branch was taken and what action follows
- Hypothesis result: confirmed/refuted/inconclusive, plus next step

If these five are captured, resume unless you need one targeted step.

### Step 8: Step and Iterate

Step through code to observe execution flow:
```
debugger_step(operation: "step_over", thread_id: <tid>)
debugger_step(operation: "step_into", thread_id: <tid>)
debugger_step(operation: "step_out", thread_id: <tid>)
```

After each step, re-inspect variables and evaluate expressions. Use watches for values you check repeatedly:
```
debugger_watch(operation: "add", expression: "sum", display_name: "Running total")
debugger_watch(operation: "evaluate", thread_id: <tid>)
```

### Step 9: Resume and Repeat

When done inspecting at this breakpoint hit:
```
debugger_session(operation: "resume_all")
```

Default behavior is to resume all threads after inspection. For deliberate `suspend_policy: "all"` concurrency snapshots, keep pauses brief and still `resume_all` before leaving the loop.

Update your sequence cursor from the last event response, then loop back to Step 6 if you expect more breakpoint hits.

In embedded mode, check async task status:
```
jshell_async(operation: "status", task_id: "<task_id>")
```

### Step 10: Diagnose and Report

Synthesize your findings:
1. State the **root cause** — exact line, exact branch/predicate value, exact variable state
2. Explain **why** the bug occurs
3. Show the **causal chain** — first divergence -> intermediate gates -> final symptom
4. Suggest the **fix** with specific code changes
5. Clean up: remove breakpoints and watches, stop session if done

```
debugger_breakpoints(operation: "remove_all")
debugger_watch(operation: "remove_all")
debugger_session(operation: "stop")
```

Definition of done:
- Root cause is proven with concrete runtime state (not just suspected)
- At least one end-to-end causal chain is validated
- A regression target is identified (test or reproducible scenario)

## The Critical Orchestration Pattern

Steps 4-9 above form an async-safe loop. The key constraint: **MCP is synchronous, so a synchronous trigger that hits a breakpoint deadlocks the channel.** The loop separates triggering (async) from inspection (sync polling).

For the full annotated walkthrough with exact tool parameters for each phase, timeout layer interactions, and error recovery patterns, see [references/orchestration.md](references/orchestration.md).

## Variable Inspection Strategy

Use progressive disclosure to minimize noise:

1. **Start at frame 0**: `get_variables` at the top stack frame first
2. **Expand selectively**: Only call `get_child_variables` for objects you need to inspect
3. **Evaluate for computed values**: Use `debugger_evaluate` for `list.size()`, `map.containsKey("x")`, `obj.getClass().getName()` — faster than expanding
4. **Check caller context**: Vary `frame_index` (0 = top, 1 = caller, 2 = caller's caller) to see how arguments were passed
5. **Static fields**: Use `get_static_fields` with `class_name` for constants, singletons, counters

Expression fallback ladder (avoid repeated dead-end evaluations):
1. Evaluate simple primitives/booleans first (`count`, `flag`, `x == null`)
2. If method resolution fails, cast explicitly (`((java.util.List)items).size()`)
3. If still failing, switch to `get_variables` + targeted `get_child_variables`
4. Do not retry the same failing expression more than twice without changing approach

## Thread Management Rules

- **Default to `resume_all` after inspection.** If you intentionally hold `suspend_policy: "all"` for concurrency analysis, do it briefly and resume before continuing.
- Default `suspend_policy` is `"thread"` — only the triggering thread suspends. This is correct for most debugging.
- Use `suspend_policy: "all"` only for race conditions where you need to freeze the entire JVM.
- Use `suspend_policy: "none"` for logging-only breakpoints (no suspension, just event).
- Find suspended threads: `debugger_threads(operation: "list", suspended_only: true)`
- Check for accidentally suspended threads before concluding "the app is hung."

## Causal Chain Validation

Do not stop after the first anomaly. Validate the chain:

1. Identify the first divergent state
2. Confirm downstream gate(s) consume that divergent state
3. Confirm those gate outcomes produce the final symptom

If step 2 or 3 is missing, keep debugging; you have correlation, not root cause.

## Timeout Attribution Rules

When timeouts are involved, classify them explicitly:

- **Debugger-influenced timeout**: observed while threads are paused/suspended long enough to consume runtime budget
- **Product timeout**: reproduced in a run with minimal/no debugger pauses

Required evidence:
1. Wall-clock timing around breakpoints
2. Application timeout/budget values at decision points
3. A low-pause confirmation run before concluding product timeout root cause

## Mode Detection

Determine your mode at session start:

**Embedded mode indicators:**
- `jshell_async` tool is available
- Full tool set (20+ tools including profiler, monitoring, log files)
- Can trigger workloads programmatically

**Proxy mode indicators:**
- Only 11 tools available: `debugger_session`, `debugger_breakpoints`, `debugger_step`, `debugger_stacktrace`, `debugger_variables`, `debugger_evaluate`, `debugger_watch`, `debugger_events`, `debugger_threads`, `thread_analyzer`, `object_inspector`
- No `jshell_async`, no `jshell_repl`
- Must use alternative trigger strategies
- `object_inspector` is context-scoped JShell inspection, not suspended-thread/frame inspection

**Proxy mode trigger alternatives:**
1. Ask the user to perform the action that exercises the code
2. Use `curl` or HTTP client via Bash to hit an endpoint
3. Have the user click a button, submit a form, or run a test
4. Set breakpoints first, then tell the user to trigger

## Launching a Debug Target

The target JVM **must** be started with JDWP enabled — it cannot be attached dynamically. If the user's application is not already running with JDWP, help them launch it.

### Using the bundled launch script (recommended for agent-driven workflows)

Use the canonical repo script [scripts/launch-managed-nontty.sh](../../../scripts/launch-managed-nontty.sh) — a supervised non-TTY process launcher with signal forwarding and PID management. It is designed for agent-driven debug target startup.

```bash
scripts/launch-managed-nontty.sh \
  --name myapp-debug \
  -- java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar target/your-application.jar
```

If you copied this skill without the repo `scripts/` directory, `.claude/skills/debug/scripts/preflight.sh` (and the wrapper) will fail fast with remediation steps. Fix by either:
- copying `scripts/launch-managed-nontty.sh` into your repository, or
- setting `DESCARTES_LAUNCH_SCRIPT=/absolute/path/launch-managed-nontty.sh`.

Options:
- `--name <name>` — logical name for PID file (default: `debug-target`, stored at `.pids/<name>.pid`)
- `--pid-file <path>` — custom PID file location
- `--cwd <dir>` — working directory for the launched command
- `--json` — emit machine-readable startup metadata (useful for automation)

This script requires non-TTY stdin/stdout/stderr (standard when launched by agents).

### Using the Descartes embedded mode script

For local development with full Descartes tooling (debugger + JShell + hot reload + profiling):

```bash
scripts/run-with-hotreload.sh
```

This auto-builds the shaded JAR if needed, finds a free MCP port (9080-9084), and starts the Descartes embedded server with Java agent flags. **You still need a separate JDWP-enabled target process** — the embedded server connects to it via JDWP, the same way an IDE debugger does. Use `scripts/launch-managed-nontty.sh` or add `-agentlib:jdwp=...` to your application's launch command to start the target.

### Using the Descartes remote proxy script

For debugging a remote or already-running JDWP target:

```bash
scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005 --mcp-port 9090
```

Defaults to `localhost:5005` → MCP `:9090`. Use `--auto-discover` to scan for JDWP processes. Use `--rebuild` to force a fresh JAR build.

### Manual JDWP launch (any Java application)

Add this JVM flag to any Java launch command:
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

For JDK 17+, also add:
```
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
```

Key JDWP options:
- `suspend=n` — JVM starts normally (use `suspend=y` to pause at startup, useful for debugging initialization code)
- `address=*:5005` — listen on all interfaces, port 5005 (use `address=5005` for localhost-only)

## Common Java Bug Patterns

Quick-reference for mapping bug types to debugging strategy:

| Bug Type | Where to Break | What to Inspect | Key Expression |
|----------|---------------|-----------------|----------------|
| Off-by-one | Loop body near boundary | Loop counter, bounds | `i < n` vs `i <= n` |
| NPE | One line before crash | The null candidate | `variable == null` |
| Integer overflow | Before the overflow op | Operands and result | `(long)a * b` |
| Wrong conditional | First line inside each branch | Which branch taken | The boolean condition |
| Collection issue | After `.add()`/`.put()` | Collection size/contents | `collection.size()` |
| Infinite loop | Inside body with hit count | Loop variable mutation | Counter or termination var |
| Deadlock | N/A — use `thread_analyzer` | Lock ownership | `thread_analyzer deadlocks` |
| Race condition | Shared state access, `suspend_policy: "all"` | Which threads access | `Thread.currentThread().getName()` |
| ClassCastException | Before the cast | Actual runtime type | `obj.getClass().getName()` |
| StackOverflow | Recursive method entry | Recursion depth | Stack frame count |

See [references/java-debug-patterns.md](references/java-debug-patterns.md) for full playbooks.

## Anti-Patterns

**These rules are critical. Violating them causes deadlocks, frozen JVMs, stale conclusions, or false root causes.**

1. **NEVER use `jshell_repl` for code that hits a breakpoint.** Deadlocks the MCP channel. Use `jshell_async` instead. (See Step 5.)

2. **NEVER leave the session with threads paused.** If you intentionally hold a global pause for concurrency inspection, still `resume_all` before continuing. (See Step 9.)

3. **NEVER ignore `since_sequence` when polling events.** Causes stale-event processing. Carry `latest_sequence` forward from every `wait`/`fetch` response. (See Step 6.)

4. **NEVER use `debugger_events clear` as routine practice.** Both `fetch` and `wait` already consume events — use cursor-based polling instead. Reserve `clear` for recovery. (See [references/orchestration.md](references/orchestration.md).)

5. **NEVER set breakpoints without reading source code first.** You need the fully qualified class name, executable line numbers, and method boundaries. (See Step 1 and Step 4.)

6. **NEVER use breakpoint spray.** Too many broad breakpoints produce noise and hide causal flow.

7. **NEVER deep-dive object graphs before capturing decision variables.** Record branch inputs/outcomes first.

8. **NEVER repeat the same failing evaluation expression.** Change strategy (cast, different frame, variable inspection).

9. **NEVER treat timeout during paused debugging as proof of product timeout behavior.** Run low-pause confirmation.

10. **NEVER keep low-signal breakpoints active after repeated noisy hits.** Disable or move them quickly.

11. **NEVER stop at the first anomaly without validating downstream impact.** Correlation is not root cause.

## Event Types

| Event Type | When Emitted |
|------------|-------------|
| `debugger.breakpoint_hit` | Thread reaches a breakpoint |
| `debugger.step_complete` | A step operation finishes |
| `debugger.exception` | Emitted only when exception event requests are enabled (not exposed by current public debugger tools) |
| `debugger.method_entry` | Emitted only when method-entry requests are enabled (not exposed by current public debugger tools) |
| `debugger.method_exit` | Emitted only when method-exit requests are enabled (not exposed by current public debugger tools) |
| `debugger.thread_start` | A new thread starts in the debuggee |
| `debugger.thread_death` | A thread exits in the debuggee |
| `debugger.vm_disconnect` | JDWP connection to the target JVM was lost |
| `debugger.breakpoint_resolved` | A deferred (pending) breakpoint was resolved after class load |
| `debugger.error` | Non-recoverable error in debugger event processing (WARNING/CRITICAL severity) |

## References

- [references/orchestration.md](references/orchestration.md) — Full async trigger + event polling loop with exact parameters
- [references/breakpoint-strategy.md](references/breakpoint-strategy.md) — Strategic breakpoint placement and line resolution
- [references/java-debug-patterns.md](references/java-debug-patterns.md) — Java bug pattern playbooks (NPE, deadlock, race conditions, etc.)
- [references/troubleshooting.md](references/troubleshooting.md) — Error codes, failure scenarios, and recovery procedures
