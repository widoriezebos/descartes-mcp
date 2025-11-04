# Descartes Debugger Workflow Example

A comprehensive demonstration of Descartes MCP's runtime debugging capabilities, featuring realistic debugging scenarios and complete workflow examples.

## Overview

This example showcases **all 8 debugger tools** through hands-on scenarios that demonstrate:

- **Session Management** - Start, stop, and configure debug sessions
- **Breakpoint Operations** - Line, conditional, and method breakpoints
- **Stepping Operations** - Step over, into, and out of methods
- **Variable Inspection** - View locals, expand objects, examine statics
- **Expression Evaluation** - Evaluate Java expressions in context
- **Watch Expressions** - Auto-evaluated watches on suspend
- **Stack Trace Analysis** - Navigate call stacks, filter frames
- **Thread Control** - List, suspend, resume, and inspect threads

## Requirements

- **JDK 11+** for JDWP (Java Debug Wire Protocol) support
- **JDK 17+** requires additional flag:
  ```bash
  --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED
  ```
- **Port 9080** available for MCP server
- **MCP Client** (optional) - Claude Desktop, custom client, etc.

## How to Run

### Automated Demo Mode (Default)

Runs all scenarios automatically with explanatory output:

```bash
mvn exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.debugger.DebuggerWorkflowExample"
```

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

**Try this workflow:**
```javascript
// 1. Start debug session
debugger_session({operation: "start"})

// 2. Set breakpoint in simpleCalculation
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.BasicDebuggingScenarios",
  line: 35  // int a = 10;
})

// 3. Trigger the method (via JShell or direct call)
jshell_repl({code: "basicScenarios.simpleCalculation()"})

// 4. Inspect variables when breakpoint hits
debugger_variables({
  operation: "getVariables",
  thread_id: <from breakpoint event>,
  frame_index: 0
})

// 5. Step over to next line
debugger_step({
  operation: "stepOver",
  thread_id: <thread_id>
})

// 6. Evaluate expression
debugger_evaluate({
  operation: "evaluate",
  thread_id: <thread_id>,
  expression: "a + b"
})

// 7. Resume execution
debugger_threads({
  operation: "resume",
  thread_id: <thread_id>
})
```

### 2. Buggy Calculator (`BuggyCalculator`)

**Purpose:** Practice finding bugs using debugger tools

**Intentional Bugs:**
1. **Off-by-one error** in `sumToN_BUGGY()` - Loop exits too early
2. **Null pointer dereference** in `calculateAverage_BUGGY()` - Missing null check
3. **Integer overflow** in `factorial_BUGGY()` - Wrong data type
4. **Wrong conditional** in `isInRange_BUGGY()` - OR instead of AND
5. **Array bounds issue** in `findSecondLargest_BUGGY()` - No validation
6. **Business logic error** in `calculateDiscount_BUGGY()` - Swapped percentages

**Bug Hunting Workflow (Off-by-one example):**
```javascript
// 1. Observe incorrect output
jshell_repl({code: "buggyCalculator.sumToN_BUGGY(10)"})
// Returns 45 instead of 55

// 2. Set breakpoint in loop
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.BuggyCalculator",
  line: 47  // Inside loop: sum += i
})

// 3. Add watch on loop variable and sum
debugger_watch({
  operation: "add",
  expression: "i",
  display_name: "Loop counter"
})

debugger_watch({
  operation: "add",
  expression: "sum",
  display_name: "Running sum"
})

// 4. Run method
jshell_repl({code: "buggyCalculator.sumToN_BUGGY(10)"})

// 5. Each time breakpoint hits, check watches
// Notice: loop stops at i=9, never reaches i=10!

// 6. Evaluate the condition
debugger_evaluate({
  operation: "evaluate",
  thread_id: <thread_id>,
  expression: "i < 10"  // True when i=9, should be i <= 10
})

// Bug found: Loop condition is wrong!
```

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

**Object Expansion Workflow:**
```javascript
// 1. Set breakpoint after object creation
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.DataStructureScenarios",
  line: 49  // After person creation in objectHierarchy()
})

// 2. Trigger method
jshell_repl({code: "dataScenarios.objectHierarchy()"})

// 3. Get top-level variables
debugger_variables({
  operation: "getVariables",
  thread_id: <thread_id>,
  frame_index: 0
})
// Returns: person (variable reference)

// 4. Expand person object
debugger_variables({
  operation: "getChildVariables",
  variable_ref: <person_variable_ref>
})
// Returns: name, age, address (another reference)

// 5. Expand address object
debugger_variables({
  operation: "getChildVariables",
  variable_ref: <address_variable_ref>
})
// Returns: street, city, zipCode

// 6. Expand city object
debugger_variables({
  operation: "getChildVariables",
  variable_ref: <city_variable_ref>
})
// Returns: name, state, population (primitives - no more expansion)
```

### 4. Concurrency Scenarios (`ConcurrencyScenarios`)

**Purpose:** Debug multi-threaded applications

**Scenarios:**
- `multipleThreads()` - Worker threads with suspend/resume
- `createDeadlock()` - Intentional deadlock for detection
- `raceCondition()` - Unsafe counter vs. AtomicInteger
- `producerConsumer()` - Blocking queue with wait/notify

**Deadlock Detection Workflow:**
```javascript
// 1. Start debug session
debugger_session({operation: "start"})

// 2. Trigger deadlock scenario
jshell_repl({code: "concurrencyScenarios.createDeadlock()"})

// 3. Wait a few seconds for deadlock to establish
// (Thread.sleep in JShell or just wait)

// 4. Detect deadlock
thread_analyzer({operation: "deadlocks"})

// Returns deadlock report:
// {
//   "deadlock_detected": true,
//   "threads_involved": ["DeadlockThread-1", "DeadlockThread-2"],
//   "circular_wait": [
//     "Thread-1 holds lock1, waiting for lock2",
//     "Thread-2 holds lock2, waiting for lock1"
//   ],
//   "stack_traces": { ... }
// }

// 5. Inspect each deadlocked thread
debugger_threads({
  operation: "list",
  name_pattern: "DeadlockThread-.*"
})

// 6. Examine stack trace of deadlocked thread
debugger_stacktrace({
  operation: "capture",
  thread_id: <deadlocked_thread_id>
})

// 7. Clean up (interrupt threads)
jshell_repl({code: "concurrencyScenarios.stopDeadlock()"})
```

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

**Exception Debugging Workflow:**
```javascript
// 1. Set breakpoint on throw statement
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.ExceptionScenarios",
  line: 97  // throw new IllegalArgumentException(...)
})

// 2. Set conditional breakpoint for specific value
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.ExceptionScenarios",
  line: 97,
  condition: "age < 0"
})

// 3. Trigger exception
jshell_repl({code: "exceptionScenarios.illegalArgumentException()"})

// 4. When breakpoint hits, inspect 'age' variable
debugger_variables({
  operation: "getVariables",
  thread_id: <thread_id>,
  frame_index: 0
})

// 5. Step into exception constructor (optional)
debugger_step({
  operation: "stepInto",
  thread_id: <thread_id>
})

// 6. Resume to catch block
debugger_threads({
  operation: "resume",
  thread_id: <thread_id>
})
```

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

**Recursive Stack Analysis Workflow:**
```javascript
// 1. Set conditional breakpoint at recursion point
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.CallStackScenarios",
  line: 44,  // In recursiveFactorial
  condition: "n == 3"
})

// 2. Trigger recursion
jshell_repl({code: "callStackScenarios.recursiveFactorial(5)"})

// 3. When breakpoint hits (n==3), capture stack
debugger_stacktrace({
  operation: "capture",
  thread_id: <thread_id>,
  max_depth: 10
})

// Returns frames showing recursion:
// Frame 0: recursiveFactorial(3)
// Frame 1: recursiveFactorial(4)
// Frame 2: recursiveFactorial(5)
// Frame 3: main (or JShell)

// 4. Inspect 'n' at each frame
debugger_variables({
  operation: "getVariables",
  thread_id: <thread_id>,
  frame_index: 0  // n=3
})

debugger_variables({
  operation: "getVariables",
  thread_id: <thread_id>,
  frame_index: 1  // n=4
})

debugger_variables({
  operation: "getVariables",
  thread_id: <thread_id>,
  frame_index: 2  // n=5
})

// 5. Resume execution
debugger_threads({
  operation: "resume",
  thread_id: <thread_id>
})
```

## Complete Debugging Workflow Example

Here's a complete end-to-end debugging session finding the off-by-one bug:

```javascript
// ===== SESSION START =====

// 1. Start debug session with configuration
debugger_session({
  operation: "start",
  jdwp_timeout: 10000,
  skip_patterns: ["java.*", "javax.*", "jdk.*"]
})

// Response: { session_id: "...", status: "active", ... }

// 2. Run buggy method to see incorrect output
jshell_repl({
  code: "var calc = new com.bitsapplied.descartes.example.debugger.scenarios.BuggyCalculator(); calc.sumToN_BUGGY(10);"
})

// Response: 45 (WRONG! Should be 55)

// 3. Set breakpoint at start of method
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.BuggyCalculator",
  line: 46,  // int sum = 0;
  enabled: true
})

// Response: { breakpoint_id: "bp-001", ... }

// 4. Add watch expressions
debugger_watch({
  operation: "add",
  expression: "i",
  display_name: "Loop counter"
})

debugger_watch({
  operation: "add",
  expression: "sum",
  display_name: "Running total"
})

debugger_watch({
  operation: "add",
  expression: "n",
  display_name: "Target value"
})

// 5. Run the method again (will hit breakpoint)
jshell_repl({code: "calc.sumToN_BUGGY(10);"})

// 6. Breakpoint hit event received - inspect variables
debugger_variables({
  operation: "getVariables",
  thread_id: <from_event>,
  frame_index: 0
})

// 7. Step over to enter loop
debugger_step({operation: "stepOver", thread_id: <tid>})

// 8. Add conditional breakpoint to stop near end of loop
debugger_breakpoints({
  operation: "set",
  class: "com.bitsapplied.descartes.example.debugger.scenarios.BuggyCalculator",
  line: 48,
  condition: "i >= 8"
})

// 9. Resume execution (will hit conditional breakpoint)
debugger_threads({operation: "resume", thread_id: <tid>})

// 10. When i==8, evaluate watches
debugger_watch({operation: "evaluate", thread_id: <tid>})
// i=8, sum=36, n=10

// 11. Resume again
debugger_threads({operation: "resume", thread_id: <tid>})

// 12. When i==9, evaluate watches
debugger_watch({operation: "evaluate", thread_id: <tid>})
// i=9, sum=45, n=10

// 13. Step over - loop should continue to i=10, but...
debugger_step({operation: "stepOver", thread_id: <tid>})

// 14. Evaluate loop condition
debugger_evaluate({
  operation: "evaluate",
  thread_id: <tid>,
  expression: "i < 10"
})
// Returns: false (loop exits!)

// 15. BUG FOUND: Loop exits when i=9, never processes i=10
//     The condition should be: i <= n, not i < n

// 16. Clean up - stop debug session
debugger_session({operation: "stop"})

// ===== SESSION END =====
```

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

- **Cause:** JDK version mismatch or permissions
- **Solution:**
  - JDK 17+: Add `--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED`
  - Check JAVA_HOME points to correct JDK

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

- [Main CLAUDE.md](../../../../../CLAUDE.md) - Project overview
- [SimpleMCPServerExample](../../SimpleMCPServerExample.java) - Integration example
- [ProfilerWorkflowExample](../profiler/ProfilerWorkflowExample.java) - Performance profiling
- [HOT_RELOAD_GUIDE.md](../../../../../HOT_RELOAD_GUIDE.md) - Hot reload debugging

## Questions or Issues?

- Check the source code comments for detailed explanations
- Review the MCP tool implementations in `com.bitsapplied.descartes.tools`
- Examine the debugger service in `com.bitsapplied.descartes.debugger`
