# Descartes Debugger Workflow Example

A comprehensive demonstration of Descartes MCP's runtime debugging capabilities, featuring realistic debugging scenarios and complete workflow examples.

## Overview

This example demonstrates **AI-assisted autonomous debugging** using Claude with Descartes MCP's debugger tools.

### What You Can Do

Instead of manually setting breakpoints and stepping through code, you can:

```
You: "My calculateDiscount method returns wrong values. Debug it."

Claude: *autonomously debugs, finds the bug, explains the issue*
```

Claude will:
- ✓ Start debug sessions automatically if needed
- ✓ Set strategic breakpoints based on the problem
- ✓ Step through code intelligently
- ✓ Track relevant variables with watches
- ✓ Evaluate expressions to understand logic
- ✓ Explain findings in plain English
- ✓ Suggest fixes (and apply them with hot reload)

### How It Works

Claude has access to **8 debugger tools** that it orchestrates autonomously:

- **Session Management** - Start, stop, and configure debug sessions
- **Breakpoint Operations** - Line, conditional, and method breakpoints
- **Stepping Operations** - Step over, into, and out of methods
- **Variable Inspection** - View locals, expand objects, examine statics
- **Expression Evaluation** - Evaluate Java expressions in context
- **Watch Expressions** - Auto-evaluated watches on suspend
- **Stack Trace Analysis** - Navigate call stacks, filter frames
- **Thread Control** - List, suspend, resume, and inspect threads

### Architectural Note: Why a Separate Process?

**You might wonder:** "Why does this example launch a separate process (`SimpleTestApplication`) instead of debugging itself?"

**Answer:** The Descartes debugger uses JDWP (Java Debug Wire Protocol), which requires the target JVM to be launched with `-agentlib:jdwp=...` from startup. HotSpot's JDWP agent lacks `Agent_OnAttach` support, meaning you cannot dynamically enable debugging on an already-running JVM—it's a fundamental limitation of the JVM, not Descartes.

**This example demonstrates "Embedded with Local Target" mode:**
```
┌─────────────────────────────────────────┐
│  DebuggerWorkflowExample Process       │
│  ┌────────────┐     ┌────────────────┐ │
│  │ Descartes  │ → → │ SimpleTestApp  │ │
│  │ (debugger) │JDWP │ (debuggee)     │ │
│  └────────────┘     └────────────────┘ │
└─────────────────────────────────────────┘
```

Descartes runs in the example's JVM but debugs a *separate* test application JVM that it launches with JDWP enabled. This is identical to how IDE debuggers (IntelliJ IDEA, Eclipse) work—they're separate processes that attach to your application via JDWP.

**Common Misconceptions Addressed:**

❌ **"Descartes should debug itself"**
✅ **Reality:** Cannot debug same JVM due to `Agent_OnAttach` limitation. Must attach to separate process.

❌ **"Why can't I just start debugging without JDWP flags?"**
✅ **Reality:** JDWP agent must be loaded at JVM startup. No dynamic attachment supported.

❌ **"This seems complicated"**
✅ **Reality:** This is how *all* Java debuggers work. IDE debuggers also connect to separate JVM processes.

**For More Details:**
- **Technical explanation:** See [doc/debugger.md](../../../../../../../../doc/debugger.md#operational-guidance) for the Agent_OnAttach limitation
- **Operational modes:** See [doc/debugger.md](../../../../../../../../doc/debugger.md#deployment-modes) for embedded vs remote proxy comparison
- **Remote debugging:** See [doc/MCPRemoteDebugProxy.md](../../../../../../../../doc/MCPRemoteDebugProxy.md) for debugging remote servers/containers

### Can Claude Actually Do This?

**Yes, Claude can autonomously chain debugger operations.** Here's how:

**Capabilities:**
- ✓ Check session status, start if needed
- ✓ Execute code and detect suspended threads
- ✓ Set breakpoints strategically based on problem description
- ✓ Add watches for relevant variables
- ✓ Step through code and inspect state at each point
- ✓ Evaluate conditions to understand logic errors
- ✓ Synthesize findings into diagnosis and recommendations

**Practical Considerations:**
- Claude narrates progress as it debugs ("Setting breakpoint... checking variables...")
- May ask clarifying questions if the problem description is ambiguous
- Uses conditional breakpoints to avoid tedious stepping
- Polls thread states to detect breakpoint hits (not push-based events)

**Interaction Style:**
You describe the problem at a high level → Claude debugs autonomously (narrating progress) → Claude reports findings and suggests fixes → You approve or ask follow-up questions

**Example:**
```
You: "My loop exits too early. Find the bug."

Claude: "Checking debug session... starting session.
         Running your code... confirmed issue.
         Setting breakpoint... adding watches for loop variables...
         Found it! Line 47 uses i < n instead of i <= n.
         Should I fix it?"
```

### Why This Matters

Traditional debugging: Set breakpoint → step → inspect → step → inspect → repeat 50 times → find bug

AI-assisted debugging: "This returns wrong values" → Claude autonomously debugs → Claude explains bug → Done

Claude handles the tedious mechanical debugging while you focus on understanding and fixing the problem.

## Requirements

- **JDK 11+** for JDWP (Java Debug Wire Protocol) support
- **JDK 17+** requires additional JVM flags (see below)
- **Port 9080** available for MCP server
- **MCP Client** (optional) - Claude Code, custom client, etc.

## How to Run

### Automated Demo Mode (Default)

Runs all scenarios automatically with explanatory output:

```bash
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample"
```

**For JDK 17+**, add these JVM flags using `exec.vmArgs`:

```bash
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample" \
  -Dexec.vmArgs="-XX:+EnableDynamicAgentLoading -Xshare:off --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED --add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED --add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED"
```

**What these flags do:**
- `-XX:+EnableDynamicAgentLoading` - Permits runtime agent loading (required for Attach API)
- `-Xshare:off` - Disables class-data sharing so Attach/JDWP can operate
- `--add-opens jdk.attach/...` - Opens Attach API internals (for process enumeration)
- `--add-opens jdk.jdi/...` - Opens JDI interfaces and implementation (for debugger)

**Note:** On JDK 11-16, most flags are not needed. Only JDK 17+ enforces strict JPMS encapsulation.

**What it does:**
- Starts MCP server on port 9080
- Runs all 6 scenario categories sequentially
- Prints educational output explaining each scenario
- Stops automatically when complete

**Use this to:**
- Understand what scenarios are available
- See example output from each scenario
- Learn the debugging workflow at a high level

### Interactive Mode

Keeps server running for manual MCP tool usage:

```bash
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample" \
  -Dexec.args="--interactive"
```

**For JDK 17+**, add the same JVM flags using `exec.vmArgs`:

```bash
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample" \
  -Dexec.args="--interactive" \
  -Dexec.vmArgs="-XX:+EnableDynamicAgentLoading -Xshare:off --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED --add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED --add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED"
```

**What it does:**
- Starts MCP server and waits
- Scenarios available via context map
- You control execution via MCP tools
- Press Enter to stop

**Use this to:**
- Practice using debugger tools interactively
- Test debugging workflows hands-on
- Experiment with breakpoints, stepping, watches
- Prepare for debugging real applications

## Debugging Scenarios

### 1. Basic Debugging Scenarios (`BasicDebuggingScenarios`)

**Purpose:** Learn fundamental debugger operations

**Scenarios:**
- `simpleCalculation()` - Stepping through arithmetic with variable inspection
- `variableInspection()` - Examining primitives, objects, and strings
- `methodCallChain()` - Step into/out practice with nested calls
- `calculateGrade(int)` - Conditional logic and expression evaluation
- `loopWithWatch()` - Watch expressions tracking loop variables
- `stringManipulation()` - Array and string object inspection

**High-level conversation (what you actually want):**

```
You: "Debug the simpleCalculation() method and show me how the variables
     flow through the calculation"

Agent: Starting debug session... analyzing simpleCalculation()...

       The method executes these steps:
       1. Line 35: a = 10
       2. Line 36: b = 20
       3. Line 37: result = a + b = 30
       4. Line 38: Returns 30

       All variables calculated correctly. No issues found.
```

**What the agent does autonomously:**
- ✓ Checks if debug session exists, starts if needed
- ✓ Sets breakpoint at method entry
- ✓ Executes the method
- ✓ Steps through each line
- ✓ Captures variable values at each step
- ✓ Validates logic flow
- ✓ Reports findings

<details>
<summary>Can Claude actually do this autonomously?</summary>

**Yes, with caveats:**

Claude CAN autonomously chain debugger operations:
- Check session status → start if needed
- Set strategic breakpoints
- Execute code and detect suspended threads
- Step through and inspect variables
- Reason about what to check next

**Current limitations:**
1. **Event Detection**: Claude needs to poll for breakpoint events by checking thread states. This works but requires calling `debugger_threads(list, suspended_only=true)` after executing code.

2. **Thread ID Tracking**: Claude must extract thread IDs from responses to use in subsequent step/inspect operations.

3. **Async Nature**: Multiple back-and-forth exchanges may be needed for complex debugging scenarios.

**Bottom line**: Claude can do high-level autonomous debugging, but the conversation may involve Claude reporting progress ("Setting breakpoint... running code... analyzing results...") rather than being completely instant.
</details>

### 2. Buggy Calculator (`BuggyCalculator`)

**Purpose:** Practice finding bugs using debugger tools

**Intentional Bugs:**
1. **Off-by-one error** in `sumToN_BUGGY()` - Loop exits too early
2. **Null pointer dereference** in `calculateAverage_BUGGY()` - Missing null check
3. **Integer overflow** in `factorial_BUGGY()` - Wrong data type
4. **Wrong conditional** in `isInRange_BUGGY()` - OR instead of AND
5. **Array bounds issue** in `findSecondLargest_BUGGY()` - No validation
6. **Business logic error** in `calculateDiscount_BUGGY()` - Swapped percentages

**Realistic interactive conversation:**

```
You: "sumToN_BUGGY(10) returns 45 instead of 55. Find the bug."

Agent: Let me debug this for you. First, I'll check if a debug session
       is active... no session found, starting one now.

       Running sumToN_BUGGY(10) to confirm... Yes, returns 45 instead
       of 55. This looks like an off-by-one error in a loop.

       Setting breakpoint at line 47 (inside the loop) and adding watches
       for i, sum, and n...

You: "Sounds good"

Agent: Executing the method with watches... The loop is iterating.
       I'm using a conditional breakpoint to skip to i >= 8 to save time.

       Interesting - when i=9, sum=45, and then the loop exits.
       Let me check the loop condition...

       Evaluating 'i < n' when i=9 and n=10... returns false, so the
       loop exits!

       🐛 **Found the bug!**

       Line 47: `for (int i = 1; i < n; i++)`

       The condition `i < n` should be `i <= n`. The loop stops at i=9,
       never processing i=10. That's why sum=45 instead of 55.

You: "Can you fix it?"

Agent: I can modify the source file and use hot reload to apply the fix
       without restarting. Should I go ahead?

You: "Yes"

Agent: Fixed line 47 to use `i <= n`. Applying hot reload...

       Testing the fix... sumToN_BUGGY(10) now returns 55. ✓

       Bug resolved!
```

**What the agent does autonomously:**
- ✓ Starts debug session if needed
- ✓ Runs the buggy method to confirm
- ✓ Sets breakpoint in loop body
- ✓ Adds watches for relevant variables (i, sum, n)
- ✓ Uses conditional breakpoint to skip to near-end of loop
- ✓ Identifies when loop terminates prematurely
- ✓ Evaluates the loop condition
- ✓ Explains the root cause
- ✓ Suggests the fix

<details>
<summary>Implementation notes</summary>

The agent chains ~10-15 tool calls to accomplish this:
1. Check/start debug session
2. Execute buggy code to confirm
3. Set strategic breakpoints
4. Add watch expressions
5. Execute and detect suspended thread
6. Step through critical iterations
7. Evaluate expressions to understand logic
8. Synthesize findings into diagnosis
</details>

### 3. Data Structure Scenarios (`DataStructureScenarios`)

**Purpose:** Master complex object and collection inspection

**Scenarios:**
- `objectHierarchy()` - Person → Address → City nested objects
- `collectionInspection()` - Lists, Maps, Sets expansion
- `nestedStructures()` - Map<String, List<Person>> hierarchies
- `circularReferences()` - Linked list with circular reference
- `staticFieldInspection()` - Static vs instance fields
- `arrayInspection()` - Single and multi-dimensional arrays
- `complexNestedObject()` - Company with departments and metadata

**Conversation for nested object inspection:**

```
You: "Set a breakpoint after the person object is created in objectHierarchy()
     and show me the nested structure"

Agent: Breakpoint set at line 49. Running the method... hit the breakpoint.

       I can see a 'person' variable. Let me expand it:

       person = Person {
         name: "Alice"
         age: 30
         address: Address {...}  ← This is another object
       }

You: "Expand the address"

Agent: address = Address {
         street: "123 Main St"
         city: City {...}  ← Another nested object
         zipCode: "12345"
       }

You: "Expand the city too"

Agent: city = City {
         name: "Springfield"
         state: "IL"
         population: 116250
       }

       That's the full hierarchy: Person → Address → City.
       All primitives at the City level, no more expansion needed.
```

<details>
<summary>Technical details</summary>

```javascript
// Agent makes hierarchical variable expansion calls:
debugger_breakpoints({operation: "set", class: "...DataStructureScenarios", line: 49})
debugger_variables({operation: "getVariables", frame_index: 0})
debugger_variables({operation: "getChildVariables", variable_ref: "person_ref"})
debugger_variables({operation: "getChildVariables", variable_ref: "address_ref"})
debugger_variables({operation: "getChildVariables", variable_ref: "city_ref"})
```
</details>

### 4. Concurrency Scenarios (`ConcurrencyScenarios`)

**Purpose:** Debug multi-threaded applications

**Scenarios:**
- `multipleThreads()` - Worker threads with suspend/resume
- `createDeadlock()` - Intentional deadlock for detection
- `raceCondition()` - Unsafe counter vs. AtomicInteger
- `producerConsumer()` - Blocking queue with wait/notify

**Realistic interactive conversation:**

```
You: "My application is hanging. I think there's a deadlock. Can you
     investigate?"

Agent: Let me check for deadlocks in your running application...

       Checking thread states... found 2 threads in BLOCKED state.

       Running deadlock detection...

       ⚠️ **Deadlock detected!**

       DeadlockThread-1 and DeadlockThread-2 are in a circular wait:

       • Thread-1 holds lock1, waiting for lock2 (line 47)
       • Thread-2 holds lock2, waiting for lock1 (line 52)

       This is a classic deadlock - each thread needs the lock held by
       the other.

You: "Show me the code where they're stuck"

Agent: Let me capture the stack traces...

       Thread-1 is blocked at:
         methodA() line 47: synchronized (lock2) { ... }
         ↑ Trying to acquire lock2

       Thread-2 is blocked at:
         methodB() line 52: synchronized (lock1) { ... }
         ↑ Trying to acquire lock1

       The issue is lock ordering. Thread-1 acquires lock1 then lock2,
       while Thread-2 acquires lock2 then lock1. This creates the
       circular dependency.

You: "How do I fix it?"

Agent: **Fix:** Always acquire locks in the same order.

       Change methodB to acquire lock1 first, then lock2 (same order as
       methodA). This prevents the circular wait condition.

       Would you like me to show you the modified code?
```

**What the agent does autonomously:**
- ✓ Checks thread states without being told
- ✓ Uses thread_analyzer to detect deadlocks
- ✓ Captures relevant stack traces
- ✓ Identifies the root cause (lock ordering)
- ✓ Suggests the proper fix

<details>
<summary>Why this works</summary>

Claude can do this because:
1. The `thread_analyzer` tool has a `deadlocks` operation that does the heavy lifting
2. Claude can reason about concurrency patterns
3. Stack traces provide concrete evidence of where threads are stuck
4. Claude synthesizes this into actionable recommendations
</details>

### 5. Exception Scenarios (`ExceptionScenarios`)

**Purpose:** Debug exception flows and error handling

**Scenarios:**
- `checkedExceptionHandling()` - IOException with try-catch
- `nullPointerException()` - NPE debugging
- `illegalArgumentException()` - Validation errors
- `deepCallStackException()` - Exception in deep call chain
- `exceptionChaining()` - Wrapped exceptions (cause analysis)
- `finallyBlockDebugging()` - Finally block execution order
- `customExceptionInspection()` - Custom exception fields

**Conversation to debug exception:**

```
You: "Set a conditional breakpoint on line 97 that only fires when age < 0"

Agent: Conditional breakpoint set. It will only pause when age is negative.

You: "Now trigger the illegalArgumentException scenario"

Agent: Breakpoint hit! The condition 'age < 0' is true.

       Current state:
       - age = -5
       - About to throw IllegalArgumentException

       The method is trying to validate age but it's negative.

You: "What's the exact error message?"

Agent: The exception message is: "Age cannot be negative: -5"

       Should I step into the exception constructor or resume to the
       catch block?

You: "Resume to the catch block"

Agent: Resumed. The exception was caught in the catch block at line 102.
       The error was logged: "Invalid age provided: -5"
```

<details>
<summary>Technical details</summary>

```javascript
// Agent sets conditional breakpoint:
debugger_breakpoints({
  operation: "set",
  class: "...ExceptionScenarios",
  line: 97,
  condition: "age < 0"  // Only breaks when true
})
jshell_repl({code: "exceptionScenarios.illegalArgumentException()"})
debugger_variables({operation: "getVariables", ...})
debugger_threads({operation: "resume", ...})
```
</details>

### 6. Call Stack Scenarios (`CallStackScenarios`)

**Purpose:** Navigate and analyze call stacks

**Scenarios:**
- `deepCallChain()` - level1 → level2 → ... → level5
- `recursiveFactorial(int)` - Classic recursion
- `recursiveFibonacci(int)` - Branching recursion
- `tailRecursiveFactorial(int)` - Tail call optimization
- `treeTraversal()` - Binary tree inorder traversal
- `isEven(int)` / `isOdd(int)` - Mutual recursion
- `demonstrateStackDepth()` - Deep recursion limits
- `dataTransformationChain()` - Pipeline transformations

**Conversation for recursive call analysis:**

```
You: "Set a conditional breakpoint in recursiveFactorial that only
     triggers when n equals 3"

Agent: Conditional breakpoint set at line 44 (n == 3).

You: "Call recursiveFactorial(5)"

Agent: Breakpoint hit! Currently in recursiveFactorial(3).

       Let me capture the call stack to show the recursion:

       Frame 0: recursiveFactorial(n=3)  ← Current frame
       Frame 1: recursiveFactorial(n=4)
       Frame 2: recursiveFactorial(n=5)  ← Original call
       Frame 3: JShell evaluation

       We're 3 levels deep in the recursion.

You: "Show me the value of n in each recursive call"

Agent: Looking at each frame:

       Frame 0 (current): n = 3
       Frame 1 (caller):  n = 4
       Frame 2 (caller):  n = 5

       The recursion will continue down to n=1 (base case) before
       unwinding back up with the computed factorials.

You: "Resume execution"

Agent: Execution resumed. Final result: 120 (which is 5!)
```

<details>
<summary>Technical details</summary>

```javascript
// Agent analyzes recursive stack:
debugger_breakpoints({operation: "set", line: 44, condition: "n == 3"})
jshell_repl({code: "callStackScenarios.recursiveFactorial(5)"})
debugger_stacktrace({operation: "capture", max_depth: 10})
debugger_variables({operation: "getVariables", frame_index: 0})  // n=3
debugger_variables({operation: "getVariables", frame_index: 1})  // n=4
debugger_variables({operation: "getVariables", frame_index: 2})  // n=5
```
</details>

## Complete Debugging Workflow Example

Here's a complete end-to-end conversation debugging the off-by-one bug:

```
===== SESSION START =====

You: "I need to debug a method called sumToN_BUGGY(10) that returns 45
     instead of 55. Start a debug session and help me find the bug."

Agent: Starting debug session with 10 second timeout and skipping Java
       framework classes...

       Let me first run the method to confirm the bug:

       BuggyCalculator calc = new BuggyCalculator();
       calc.sumToN_BUGGY(10) → returns 45

       Confirmed - should be 55 (sum of 1 to 10).

       Setting breakpoint at line 46 (start of method)...

You: "Add watch expressions for the loop counter i, the running sum, and n"

Agent: Added watches:
       - "i" (Loop counter)
       - "sum" (Running total)
       - "n" (Target value)

       Running the method again... breakpoint hit at line 46.

You: "Step over and monitor the watches"

Agent: Stepping through the loop...

       Iteration 1: i=1, sum=1, n=10
       Iteration 2: i=2, sum=3, n=10
       Iteration 3: i=3, sum=6, n=10
       ...

       This will take a while. Let me set a conditional breakpoint
       at line 48 that only triggers when i >= 8.

You: "Good idea, resume execution"

Agent: Conditional breakpoint hit! i=8, sum=36, n=10
       Resuming...

       Conditional breakpoint hit again! i=9, sum=45, n=10

You: "That's the last iteration. Step over and check if the loop continues"

Agent: Stepped over... the loop exited! We're now past the loop.

       Let me evaluate the loop condition: 'i < 10'
       Result: false (because i=9)

       **🐛 BUG FOUND!**

       The loop condition is 'i < n' but it should be 'i <= n'.
       When i=9, the condition 'i < 10' is false, so the loop exits
       without processing i=10. That's why the sum is 45 instead of 55.

You: "Stop the debug session"

Agent: Debug session stopped. Summary:

       Bug: Line 47 - loop condition should be 'i <= n' not 'i < n'
       Impact: Final iteration (i=n) is skipped
       Fix: Change 'for (int i = 1; i < n; i++)' to 'i <= n'

===== SESSION END =====
```

<details>
<summary>Technical details (tool calls the agent made)</summary>

```javascript
debugger_session({operation: "start", jdwp_timeout: 10000})
jshell_repl({code: "var calc = new ...BuggyCalculator(); calc.sumToN_BUGGY(10);"})
debugger_breakpoints({operation: "set", line: 46})
debugger_watch({operation: "add", expression: "i", display_name: "Loop counter"})
debugger_watch({operation: "add", expression: "sum", display_name: "Running total"})
debugger_watch({operation: "add", expression: "n", display_name: "Target value"})
jshell_repl({code: "calc.sumToN_BUGGY(10);"})
debugger_step({operation: "stepOver", thread_id: <tid>})
debugger_breakpoints({operation: "set", line: 48, condition: "i >= 8"})
debugger_threads({operation: "resume", thread_id: <tid>})
debugger_watch({operation: "evaluate", thread_id: <tid>})
debugger_evaluate({operation: "evaluate", expression: "i < 10"})
debugger_session({operation: "stop"})
```
</details>

## Debugger Tools Reference

### 1. `debugger_session` - Session Management

**Operations:**
- `start` - Start new debug session
- `stop` - Stop active session
- `status` - Get session state

**Example:**
```javascript
debugger_session({
  operation: "start",
  jdwp_timeout: 5000,
  skip_patterns: ["java.*", "sun.*"]
})
```

### 2. `debugger_breakpoints` - Breakpoint Control

**Operations:**
- `set` - Create breakpoint (line or conditional)
- `remove` - Remove breakpoint by ID
- `removeAll` - Clear all breakpoints
- `list` - View all breakpoints
- `enable` / `disable` - Toggle breakpoint

**Examples:**
```javascript
// Line breakpoint
debugger_breakpoints({
  operation: "set",
  class: "com.example.MyClass",
  line: 42
})

// Conditional breakpoint
debugger_breakpoints({
  operation: "set",
  class: "com.example.MyClass",
  line: 42,
  condition: "count > 100"
})
```

### 3. `debugger_step` - Stepping Operations

**Operations:**
- `stepOver` - Execute next line (don't enter methods)
- `stepInto` - Enter method calls
- `stepOut` - Exit current method to caller

**Example:**
```javascript
debugger_step({
  operation: "stepOver",
  thread_id: 12345
})
```

### 4. `debugger_threads` - Thread Control

**Operations:**
- `list` - List all threads (with filters)
- `inspect` - Detailed thread info
- `suspend` - Suspend specific thread
- `resume` - Resume suspended thread
- `resumeAll` - Resume all threads

**Examples:**
```javascript
// List all RUNNABLE threads
debugger_threads({
  operation: "list",
  state_filter: "RUNNABLE"
})

// List threads matching pattern
debugger_threads({
  operation: "list",
  name_pattern: "Worker-.*"
})
```

### 5. `debugger_stacktrace` - Stack Inspection

**Operations:**
- `capture` - Full stack trace
- `captureFiltered` - Exclude framework packages
- `getFrame` - Specific frame by index
- `getCurrentFrame` - Top of stack

**Example:**
```javascript
debugger_stacktrace({
  operation: "capture",
  thread_id: 12345,
  max_depth: 20
})
```

### 6. `debugger_variables` - Variable Inspection

**Operations:**
- `getVariables` - Locals in stack frame
- `getChildVariables` - Expand object properties
- `getStaticFields` - Class static fields

**Examples:**
```javascript
// Get locals in current frame
debugger_variables({
  operation: "getVariables",
  thread_id: 12345,
  frame_index: 0
})

// Expand object
debugger_variables({
  operation: "getChildVariables",
  variable_ref: "ref-abc123"
})
```

### 7. `debugger_evaluate` - Expression Evaluation

**Operation:**
- `evaluate` - Evaluate Java expression in context

**Example:**
```javascript
debugger_evaluate({
  operation: "evaluate",
  thread_id: 12345,
  expression: "person.getName().length() > 5"
})
```

**Security Warning:** Can execute arbitrary code! Development only!

### 8. `debugger_watch` - Watch Expressions

**Operations:**
- `add` - Add watch expression
- `remove` - Remove watch by ID
- `removeAll` - Clear all watches
- `list` - View all watches
- `enable` / `disable` - Toggle watch
- `evaluate` - Manually evaluate all watches

**Example:**
```javascript
debugger_watch({
  operation: "add",
  expression: "count",
  display_name: "Item count"
})
```

## Tips and Best Practices

### Setting Effective Breakpoints

1. **Use conditional breakpoints** for specific cases:
   ```javascript
   condition: "i == 100"        // Break on specific iteration
   condition: "result == null"  // Break on null
   condition: "count > threshold && enabled"  // Complex conditions
   ```

2. **Start broad, then narrow** - Set breakpoint before problematic area, step through to find exact issue

3. **Use method breakpoints** for entry points:
   ```javascript
   // Not yet exposed via tools, but available via MethodBreakpointManager
   ```

### Efficient Variable Inspection

1. **Lazy expansion** - Only expand objects you need to inspect
2. **Use watches** for values you check repeatedly
3. **Evaluate expressions** instead of expanding complex objects
4. **Filter static fields** by class to reduce noise

### Debugging Concurrent Code

1. **Suspend individual threads** to freeze specific execution
2. **Use thread name patterns** to filter relevant threads
3. **Check for deadlocks** with `thread_analyzer` tool
4. **Examine lock ownership** in thread inspector

### Performance Tips

1. **Disable unused breakpoints** instead of removing (faster re-enable)
2. **Limit conditional breakpoints** (evaluated on every execution)
3. **Use skip patterns** to avoid stepping into framework code
4. **Remove watches** when no longer needed

## Troubleshooting

### "Unable to attach debugger"

- **Cause:** JDK version mismatch, missing JVM flags, or permissions
- **Solution:**
  - **JDK 17+**: Add ALL required flags (see "How to Run" section above):
    - `-XX:+EnableDynamicAgentLoading`
    - `-Xshare:off`
    - `--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED`
    - `--add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED`
    - `--add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED`
  - Check JAVA_HOME points to correct JDK (JDK 11+ required)

### "Breakpoint not hit"

- **Cause:** Class not loaded yet, wrong line number, or condition never true
- **Solution:**
  - Verify class name (use full package)
  - Check line number matches actual code
  - Test condition with `debugger_evaluate`

### "Variable reference expired"

- **Cause:** Thread resumed before expansion complete
- **Solution:**
  - Suspend thread before variable inspection
  - Complete expansion before resuming

### "Evaluation timeout"

- **Cause:** Expression takes too long or causes deadlock
- **Solution:**
  - Simplify expression
  - Check for synchronization issues in expression
  - Increase JDWP timeout

## Output Location

All demo output and logs are saved to: `./debugger-demo-output/`

## Next Steps

1. **Run automated demo** to see all scenarios
2. **Run interactive mode** and practice manually
3. **Read scenario source code** to understand examples
4. **Integrate into your application** following SimpleMCPServerExample pattern
5. **Create custom scenarios** for your specific debugging needs

## See Also

- [Main AGENTS.md](../../../../../../../../AGENTS.md) - Canonical repository instructions
- [SimpleMCPServerExample](../SimpleMCPServerExample.java) - Integration example
- [ProfilerWorkflowExample](../profiler/ProfilerWorkflowExample.java) - Performance profiling
- [Hot Reload Guide](../../../../../../../../doc/hot-reload.md) - Hot reload debugging

## Questions or Issues?

- Check the source code comments for detailed explanations
- Review the MCP tool implementations in `com.bitsapplied.descartes.tools`
- Examine the debugger service in `com.bitsapplied.descartes.debugger`
