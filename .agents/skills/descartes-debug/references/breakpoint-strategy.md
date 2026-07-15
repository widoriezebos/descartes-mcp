# Breakpoint Strategy

## Read Source Code First

Before setting any breakpoint, **always use the Read tool** to examine the source file. You need to know:

1. **Fully qualified class name** — package + class (e.g., `com.example.service.OrderService`)
2. **Executable lines** — not comments, blank lines, or declarations. Only statements, assignments, method calls, and control flow lines are executable.
3. **Method boundaries** — where methods start and end, so `strict_same_method` works correctly
4. **What the code does** — understand the logic before choosing where to break

Skipping this step leads to breakpoints on non-executable lines, wrong class names, and wasted debugging cycles.

## Line Resolution Behavior

The breakpoint system supports two line resolution modes:

### `line_mode: "closest"` (default)
If the requested line is not executable, snaps to the nearest executable line within `max_line_delta` (default: 3 lines).

**Guards:**
- `strict_same_method: true` (default) — rejects snapping to a line in a different method
- `max_line_delta: 3` (default) — rejects snapping if the distance exceeds this

### `line_mode: "exact"`
Fails if the requested line is not executable. Returns error `BREAKPOINT_LINE_NOT_EXECUTABLE` (1105).

### Response Fields
Every `set`/`upsert`/`resolve_line` response includes:
- `requested_line` — the line you asked for
- `resolved_line` — the actual executable line used
- `resolved_method` — the method containing the resolved line
- `resolved_class` — the class containing the resolved line
- `line_delta` — `resolved_line - requested_line` (0 if exact match)
- `resolution_mode` — `"exact"` or `"closest"`
- `status_detail` — `"created"`, `"updated"`, or `"unchanged"` (for `set`/`upsert`)

### Preflight with `resolve_line`
Use `resolve_line` to check line executability without creating a breakpoint:
```
debugger_breakpoints(
  operation: "resolve_line",
  class_name: "com.example.MyClass",
  line_number: 42
)
```
If it returns a resolved line, you know the breakpoint will work. If it fails, adjust your line number.

## Strategic Placement by Bug Type

| Bug Type | Where to Break | Why |
|----------|---------------|-----|
| Wrong return value | Line before `return` statement | See the computed value before it leaves the method |
| Off-by-one | Inside loop body near boundary logic | Check boundary state (`i`, bounds) when loop exits |
| NullPointerException | One line before the crash line | See which reference is null before it explodes |
| Wrong conditional | First line inside each branch | See which branch is actually taken |
| Collection issue | After `.add()`, `.put()`, `.remove()` calls | Verify the mutation happened correctly |
| Infinite loop | Inside loop body with iteration condition | Detect non-termination, check loop variable mutation |
| ConcurrentModification | At collection modification points | Identify which thread is modifying during iteration |
| Integer overflow | Before the arithmetic operation | See operand values before overflow |
| ClassCastException | Before the cast expression | Check actual runtime type |
| StackOverflowError | At recursive method entry | Observe recursion depth and base case evaluation |

### Placement Guidelines

**For loops:** Break inside the loop body, not at the `for`/`while` line. Use `debugger_evaluate` at each hit to gate quickly (e.g., `i >= n-2`, `i % 1000 == 0`), then resume immediately when the gate is false.

**For conditionals:** Break at the first line inside each branch, not at the `if` line itself. This tells you which branch was taken without needing to evaluate the condition manually.

**For method calls:** Break at the line after the call to see the return value, or use `step_into` to enter the method and observe its internals.

**For exception handlers:** Break inside the `catch` block to see the caught exception, then evaluate `exception.getMessage()` and `exception.getCause()`.

## Condition Field (Current Runtime Behavior)

`debugger_breakpoints` accepts a `condition` string and stores it on the breakpoint metadata. In current runtime behavior, this field should **not** be treated as a reliable hit filter.

Use condition expressions as debugging intent labels, then enforce the gate manually at breakpoint hits with `debugger_evaluate`.

### Common Patterns

```java
// Specific iteration
i == 99

// Null check
name == null

// Collection size
list.size() > 100

// Negative value
amount < 0

// Periodic (every 1000th)
count % 1000 == 0

// String match
name.equals("Alice")

// Thread-specific
Thread.currentThread().getName().contains("Worker")

// Compound
order != null && order.getTotal() < 0

// Array bounds
index >= array.length
```

### Manual Gating Pattern

1. Set breakpoint at the line of interest (optional `condition` for traceability in `list` output).
2. On hit, evaluate the gate expression in the suspended frame:
   - `debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "i >= n-2")`
3. If false, resume immediately; if true, inspect deeply.

## Deferred Breakpoints

When the target class is not yet loaded (common with Spring beans, plugin systems, lazily loaded code), use `defer_if_unloaded: true` (this is the default):

```
debugger_breakpoints(
  operation: "set",
  class_name: "com.example.LazyService",
  line_number: 30,
  defer_if_unloaded: true
)
```

**Breakpoint states:**
- `pending` — class not yet loaded. Breakpoint is stored and will be set when the class loads.
- `verified` — class is loaded and the breakpoint is installed in the JVM.
- `failed` — the breakpoint could not be set (e.g., invalid location after class load).

Check breakpoint status with:
```
debugger_breakpoints(operation: "list")
```

The transition from `pending` to `verified` happens automatically when the JVM loads the class. No action needed from you.

**When to use `defer_if_unloaded: false`:**
Only when you want immediate feedback that a class doesn't exist (typo in class name, wrong package). Setting `defer_if_unloaded: false` will fail immediately if the class isn't loaded, giving you error `BREAKPOINT_CLASS_NOT_FOUND` (1104).

## Breakpoint Idempotency

Calling `set` at the same class+line location is idempotent:
- First call: `status_detail: "created"` — new breakpoint installed
- Second call with same params: `status_detail: "unchanged"` — no change
- Second call with different condition: `status_detail: "updated"` — condition changed

Use `upsert` as an explicit synonym for `set` when you want to emphasize the update-or-create semantics.

The response always includes the `breakpoint_id`, which you need for `remove`, `enable`, and `disable` operations.

## Multiple Breakpoints

You can have multiple breakpoints active simultaneously. Common patterns:

**Entry + exit:** Break at method entry and at the line before `return` to see input vs output.

**Multiple branches:** Break inside each branch of an if-else to see which path is taken.

**Pipeline stages:** Break at the output of each transformation stage to see intermediate values.

**Cross-method:** Break in method A and method B to see how data flows between them.

Clean up with `remove_all` when you're done, or `remove` specific breakpoints by ID.
