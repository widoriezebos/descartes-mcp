# Java Debug Patterns

Playbooks for debugging common Java bug categories. Each pattern includes a decision tree, breakpoint strategy, inspection steps, and key expressions to evaluate.

## 1. NullPointerException Investigation

**Decision tree:**

1. Read the crash line from the stack trace
2. Identify all references dereferenced on that line (method calls, field accesses)
3. Set a breakpoint one line before the crash
4. Inspect which reference is null

**Breakpoint strategy:**
```
debugger_breakpoints(
  operation: "set",
  class_name: "<crash class>",
  line_number: <crash line - 1>
)
```

**Inspection steps:**
1. When breakpoint hits, `get_variables` at frame 0
2. Identify the null candidate(s) from the variable list
3. If the null was returned from a method call, `step_into` that method on a re-run to trace the null source
4. Walk up the call stack with `get_variables` at frame 1, 2, etc. to find where the null originated

**Key expressions:**
```java
variable == null          // Confirm null
variable.getClass()       // If not null, see actual type
```

**For intermittent NPE debugging (null only sometimes):**
```
debugger_breakpoints(
  operation: "set",
  class_name: "<class>",
  line_number: <line>
)
```
At hit time, gate manually:
```
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "variable == null")
```

## 2. ConcurrentModificationException

**The bug:** One thread iterates a collection while another thread modifies it.

**Decision tree:**
1. Identify the collection being iterated (from the stack trace)
2. Find all code paths that modify this collection
3. Set breakpoints at each modification point
4. Use `suspend_policy: "all"` to freeze the entire JVM on hit
5. Check which threads are active and what they're doing

**Breakpoint strategy:**
Set breakpoints at every `.add()`, `.remove()`, `.put()`, `.clear()` call on the collection:
```
debugger_breakpoints(
  operation: "set",
  class_name: "<modifier class>",
  line_number: <modification line>,
  suspend_policy: "all"
)
```

**Inspection steps:**
1. When breakpoint hits (all threads frozen), check `debugger_threads(operation: "list")`
2. Look for other threads that are mid-iteration on the same collection
3. Capture stack traces for both the modifying and iterating threads
4. The fix is usually wrapping with `Collections.synchronizedList()`, using `ConcurrentHashMap`, or copying before iteration

**Key expressions:**
```java
Thread.currentThread().getName()
collection.size()
```

## 3. Deadlock Detection and Analysis

**The bug:** Two or more threads are each waiting for a lock held by the other.

**Decision tree:**
1. Use `thread_analyzer` to detect deadlocks automatically
2. If confirmed, inspect the lock chains
3. Identify the lock ordering violation
4. Recommend consistent lock ordering

**Step-by-step:**

```
thread_analyzer(operation: "deadlocks")
```

If deadlocks are found, inspect the involved threads:
```
thread_analyzer(
  operation: "thread_inspect",
  thread_ids: [<thread_id_1>, <thread_id_2>],
  include_locks: true,
  include_stack: true
)
```

**What to look for:**
- Thread A holds Lock1, waiting for Lock2
- Thread B holds Lock2, waiting for Lock1
- This is a circular dependency

**Fix patterns:**
- **Consistent ordering:** Always acquire locks in the same global order
- **Lock timeout:** Use `tryLock(timeout)` instead of `synchronized`
- **Reduce granularity:** Use finer-grained locks or lock-free data structures
- **Single lock:** If both locks protect related state, consider one lock

## 4. Race Condition Detection

**The bug:** Multiple threads access shared state without proper synchronization, producing non-deterministic results.

**Decision tree:**
1. Identify the shared variable/field
2. Find all access points (reads and writes)
3. Set breakpoints with `suspend_policy: "all"` at access points
4. Check which threads access the state and whether synchronization is used

**Breakpoint strategy:**
```
debugger_breakpoints(
  operation: "set",
  class_name: "<class>",
  line_number: <shared state access>,
  suspend_policy: "all"
)
```

**Inspection steps:**
1. On breakpoint hit, list all threads: `debugger_threads(operation: "list")`
2. Check thread states — are multiple threads RUNNABLE near the shared state?
3. Evaluate lock status:
   ```
   debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "Thread.holdsLock(lockObject)")
   ```
4. Check the shared variable value from different threads (switch `thread_id`)

**Key expressions:**
```java
Thread.currentThread().getName()
Thread.holdsLock(this)
Thread.holdsLock(lockObject)
counter                          // Check for torn reads
```

**Fix patterns:**
- `synchronized` blocks around shared state access
- `AtomicInteger`, `AtomicReference`, `AtomicLong` for simple counters
- `ConcurrentHashMap` instead of `HashMap`
- `volatile` for visibility-only needs (no compound operations)

## 5. Memory Leak Indicators

**The bug:** Collections or caches grow without bound, eventually causing OutOfMemoryError.

**Decision tree:**
1. Identify suspect collections (caches, listeners, session maps)
2. Set breakpoints at the add/put methods
3. Evaluate collection size across multiple hits
4. Look for missing removal/cleanup

**Breakpoint strategy:**
```
debugger_breakpoints(
  operation: "set",
  class_name: "<class>",
  line_number: <after cache.put()>
)
```

**Inspection steps:**
1. On each hit, evaluate `cache.size()` (optionally gate with `cache.size() % 100 == 0`)
2. Resume and wait for next hit
3. Compare sizes — is the collection growing without bound?
4. Check for corresponding removal logic
5. For thread leaks: `thread_analyzer(operation: "thread_dump")` — look for accumulating threads

**Key expressions:**
```java
cache.size()
listeners.size()
sessions.size()
Runtime.getRuntime().freeMemory()
Runtime.getRuntime().totalMemory()
```

## 6. Spring/Framework Debugging

**Request flow tracing:** Controller → Service → Repository

**Strategy:** Set breakpoints at each layer boundary and step through.

**Filtered stack traces** to remove framework noise:
```
debugger_stacktrace(
  operation: "capture_filtered",
  thread_id: <tid>,
  exclude_patterns: ["org.springframework.*", "java.*", "javax.*", "jdk.*", "sun.*", "org.apache.*"]
)
```

**Deferred breakpoints for Spring beans:**
Spring beans are loaded lazily. Use `defer_if_unloaded: true` (default) so the breakpoint activates when Spring loads the class:
```
debugger_breakpoints(
  operation: "set",
  class_name: "com.example.service.UserService",
  line_number: 45,
  defer_if_unloaded: true
)
```

**Debugging `@PostConstruct`:**
Set a deferred breakpoint inside the `@PostConstruct` method. It will activate when Spring loads the bean class and fire during initialization.

**Proxy objects:** Spring may wrap your beans in proxies (CGLIB, JDK dynamic proxy). If `step_into` enters a proxy method, use `step_out` then `step_into` again to reach the actual implementation, or use `capture_filtered` to see through the proxy layers.

## 7. Exception Chain Unwinding

**The bug:** An exception is wrapped multiple times, hiding the root cause.

**Decision tree:**
1. Break inside the outermost `catch` block
2. Evaluate the exception and its cause chain
3. Find the original root cause

**Breakpoint strategy:**
Break inside the `catch` block that handles the wrapped exception.

**Inspection steps:**
```
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "exception.getMessage()")
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "exception.getCause()")
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "exception.getCause().getMessage()")
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "exception.getCause().getCause()")
```

Continue chaining `.getCause()` until you get `null` — the last non-null cause is the root cause.

**For deeper chains:**
```java
// Find root cause
Throwable root = exception;
while (root.getCause() != null) root = root.getCause();
root.getMessage()
```
Use `debugger_evaluate` with this multi-line expression (JShell evaluator handles it).

## 8. Collection Corruption

**The bug:** Collection contains unexpected data (wrong size, wrong elements, duplicates, missing entries).

**Decision tree:**
1. Break after the collection is populated
2. Evaluate collection size and contents
3. For large collections, use stream/filter expressions
4. For maps, check specific keys

**Key expressions:**
```java
collection.size()
collection.toString()                    // Small collections only
collection.contains(expectedValue)
collection.stream().filter(x -> x == null).count()
collection.stream().distinct().count()   // Check for duplicates

// For Maps
map.size()
map.containsKey("expectedKey")
map.get("key")
map.entrySet().stream().filter(e -> e.getValue() == null).count()
```

**For very large collections** (avoid `.toString()` which serializes everything):
```java
collection.size()                                          // Just the count
collection.stream().limit(10).collect(Collectors.toList()) // First 10 elements
collection.stream().filter(predicate).findFirst()          // Search for specific element
```

## 9. ClassCastException

**The bug:** Runtime type doesn't match the expected type at a cast or generic boundary.

**Decision tree:**
1. Identify the cast location from the stack trace
2. Break one line before the cast
3. Check the actual runtime type

**Key expressions:**
```java
variable.getClass().getName()              // Actual runtime type
variable.getClass().getSuperclass()        // Parent class
variable instanceof ExpectedType           // Type check
variable.getClass().getInterfaces()        // Implemented interfaces
```

**Common causes:**
- Generic type erasure (List<String> actually contains Integer at runtime)
- Incorrect deserialization
- Factory methods returning the wrong subtype
- Plugin/classloader issues (same class loaded by different classloaders)

## 10. StackOverflowError

**The bug:** Infinite or excessively deep recursion.

**Decision tree:**
1. Identify the recursive method from the stack trace (repeated frame)
2. Set a breakpoint at the method entry
3. Check why the base case isn't reached

**Breakpoint strategy:**
Use a method-entry breakpoint, then evaluate depth indicators manually:
```
debugger_breakpoints(
  operation: "set",
  class_name: "<class>",
  line_number: <method entry>
)
```

At hit time (if explicit depth variable exists):
```
debugger_evaluate(operation: "evaluate", thread_id: <tid>, expression: "depth > 100")
```

If there's no explicit depth parameter, set an unconditional breakpoint and check the stack depth:
```
debugger_stacktrace(operation: "capture", thread_id: <tid>, max_depth: 500)
```
Count the frames to see the recursion depth.

**What to check:**
1. The base case condition — is it ever true?
2. The recursive argument — is it converging toward the base case?
3. Mutual recursion — is A calling B calling A without a termination path?

**Key expressions:**
```java
n                    // Recursive parameter
depth                // Explicit depth counter
n == 0               // Base case condition
n - 1                // Next recursive argument — does it decrease?
```
