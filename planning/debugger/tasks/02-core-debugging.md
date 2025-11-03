# Phase 2: Core Debugging

**Timeline**: Week 3-4
**Status**: Not Started
**Priority**: P0 (Blocking)
**Dependencies**: Phase 1 Complete

---

## Overview

This phase implements the core debugging features that allow a user to control and inspect the flow of an application:
- Breakpoint management (line, conditional, exception)
- Stepping operations (step in/over/out, continue, pause)
- Stack trace inspection
- The corresponding MCP tools for each feature

To ensure this phase is self-contained, a **mock expression evaluator** will be created to support conditional breakpoints, deferring the implementation of the full-featured evaluator to Phase 4.

**Success Criteria**:
- Can set and hit line breakpoints.
- Conditional breakpoints work correctly using a mock evaluator.
- All stepping operations (in, over, out, continue) are functional.
- Exception breakpoints correctly catch specified exceptions.
- Stack traces can be retrieved for any suspended thread.
- All features are exposed and testable via their respective MCP tools.

---

## Task 2.1: Breakpoint Base Infrastructure

**Time**: 8 hours

### Description
Create the foundational classes and interfaces for managing all types of breakpoints.

### Subtasks
1.  **Create `IBreakpoint.java` Interface**:
    ```java
    public interface IBreakpoint extends AutoCloseable {
        String getId();
        String getClassName();
        int getLineNumber();
        boolean isVerified();
        String getCondition();
        Integer getHitCount();
        String getLogMessage();

        CompletableFuture<IBreakpoint> install();
        void close() throws Exception; // Renamed from dispose for clarity

        List<EventRequest> getJdiRequests();
        Map<String, Object> getProperties();
        void setProperty(String key, Object value);
    }
    ```
2.  **Implement `LineBreakpoint.java`**:
    - This class will handle the logic for standard line breakpoints.
    - It must create `BreakpointRequest`s for resolved locations.
    - It must handle deferred breakpoints by listening for `ClassPrepareEvent` if a class is not yet loaded.
    - It must subscribe to `BreakpointEvent` to detect when it is hit.
3.  **Implement `BreakpointManager.java`**:
    - `setBreakpoints(source, specs)`: Computes the delta between existing and new breakpoints to add, remove, or update.
    - `removeBreakpoint(id)`: Removes a specific breakpoint and cleans up its JDI request.
    - `getBreakpoints()`: Lists all active breakpoints.
    - Manages the subscription to `ClassPrepareEvent` for all pending deferred breakpoints.

### Tests
- Test breakpoint creation and installation in a loaded class.
- Test that a breakpoint for a not-yet-loaded class is successfully deferred and then installed when the class is prepared.
- Test breakpoint hit detection and removal.

**Acceptance**: Can set, hit, and remove line breakpoints. Deferred installation works reliably.

---

## Task 2.2: Conditional Breakpoints and Mock Evaluator

**Time**: 6 hours

### Description
Implement conditional breakpoints. To break the dependency on the Phase 4 expression evaluator, this task includes creating a mock `IEvaluationProvider`.

### Subtasks
1.  **Create `IEvaluationProvider.java` Interface**:
    ```java
    public interface IEvaluationProvider {
        CompletableFuture<Value> evaluate(String expression, ThreadReference thread, StackFrame frame);
    }
    ```
2.  **Create `MockEvaluationProvider.java`**:
    - This is a temporary implementation for use in Phase 2 and 3.
    - It will implement simple, predictable logic. For example:
        - If `expression.contains("true")`, return `BooleanValue(true)`.
        - If `expression.contains("false")`, return `BooleanValue(false)`.
        - Otherwise, throw a `DebuggerException` indicating the expression is not supported by the mock provider.
    - This allows the full conditional breakpoint workflow to be tested without the real evaluator.
3.  **Implement `ConditionalBreakpoint.java`**:
    ```java
    public class ConditionalBreakpoint extends LineBreakpoint {
        private final String condition;
        private final IEvaluationProvider evaluationProvider;

        // Constructor accepts an IEvaluationProvider

        // On breakpoint hit (on the debuggerExecutor):
        // 1. Invoke evaluationProvider.evaluate(condition, thread, frame).
        // 2. If the future completes with a BooleanValue of true, allow the thread to remain suspended.
        // 3. If it completes with false or an error, resume the thread immediately.
    }
    ```
4.  **Integrate into `DebuggerService`**:
    - The `DebuggerService` will instantiate the `MockEvaluationProvider` and pass it to the `BreakpointManager`.

### Tests
- A breakpoint with condition `"x == true"` stops execution.
- A breakpoint with condition `"y == false"` does not stop execution.
- An invalid condition (e.g., `"z > 10"`) results in an error that is logged, and the thread resumes.

**Acceptance**: Conditional breakpoints can be set and function correctly using the mock evaluator. The dependency on Phase 4 is successfully stubbed out.

---

## Task 2.3: Exception Breakpoints

**Time**: 6 hours

### Description
Implement the ability to break when specific (or all) exceptions are thrown.

### Subtasks
1.  **Implement `ExceptionBreakpointHandler.java`**:
    - `setExceptionBreakpoints(config)`: This method will create or update the single `ExceptionRequest` for the VM.
    - The handler will manage filters for caught/uncaught, specific exception types (by name), and class inclusion/exclusion patterns.
    - It will subscribe to `ExceptionEvent` and decide whether to suspend the thread based on the active configuration.
2.  **Integrate into `DebuggerService`**:
    - Add a `setExceptionBreakpoints(config)` method that delegates to the handler.

### Tests
- Test breaking on a specific caught exception (`NullPointerException`).
- Test that uncaught exceptions are correctly intercepted.
- Test that class filters (e.g., "skip `java.*`") work as expected.

**Acceptance**: Exception breakpoints can be configured and correctly suspend the VM when an exception is thrown.

---

## Task 2.4: Stepping Operations

**Time**: 10 hours

### Description
Implement the core stepping logic (in, over, out) and state management.

### Subtasks
1.  **Implement `StepRequestHandler.java`**:
    - `stepInto(thread, filters)`
    - `stepOver(thread, filters)`
    - `stepOut(thread)`
    - These methods will create the appropriate JDI `StepRequest` (`STEP_INTO`, `STEP_OVER`, `STEP_OUT`) with a count filter of 1.
    - The handler will subscribe to `StepEvent`.
2.  **Implement Smart Filtering**:
    - When a `StepEvent` occurs, the handler must inspect the location.
    - If the location is in a class matching an exclusion filter (e.g., `java.*`), it should automatically create a *new* step request to move out of the filtered code, rather than suspending.
    - This provides a smoother "just my code" stepping experience.
3.  **Update `ThreadStateManager.java`**:
    - Track the pending `StepRequest` for each thread.
    - This is crucial for knowing when a suspension is due to a step operation.
    - Ensure state is cleared on `ThreadDeathEvent`.

### Tests
- `stepInto` correctly enters a method call on the same line.
- `stepOver` executes a method call without stopping inside it.
- `stepOut` correctly runs until the current method returns.
- Step filtering correctly skips over methods in `java.util.*`.

**Acceptance**: All stepping operations work correctly and reliably, including with filters.

---

## Task 2.5: Stack Trace Inspection

**Time**: 6 hours

### Description
Implement the logic to retrieve and represent the call stack of a suspended thread.

### Subtasks
1.  **Implement `StackTraceHandler.java`**:
    - `getStackTrace(thread, startFrame, maxDepth)`: Retrieves frames from the `ThreadReference`.
    - `frameToInfo(StackFrame)`: Converts a JDI `StackFrame` into our `StackFrameInfo` model. This includes the method name, class name, source file, and line number.
2.  **Implement `StackFrameManager.java`**:
    - This class is responsible for creating stable, unique IDs for stack frames. A simple approach is to generate an ID and map it to a `(threadId, frameDepth)` tuple.
    - `createFrameId(thread, depth)` → returns a unique integer ID.
    - `getStackFrame(frameId)` → looks up the tuple and returns the corresponding `StackFrame` from the `ThreadReference`.
    - This is necessary because JDI `StackFrame` objects are not stable across JDI calls.
    - Ensure the ID map is cleared when the thread resumes.

### Tests
- Get a full stack trace from a suspended thread.
- Test pagination (`startFrame`, `maxDepth`).
- Verify that frame IDs are stable as long as the thread remains suspended.

**Acceptance**: Can retrieve accurate and stable stack traces for suspended threads.

---

## Task 2.6: MCP Tool - debugger_breakpoints

**Time**: 6 hours

### Description
Create the MCP tool for managing all breakpoint types.

### Subtasks
1.  **Implement `DebuggerBreakpointsTool.java`**:
    - Implement `set`, `remove`, and `list` operations.
    - The `set` operation should handle line, conditional, and logpoint breakpoints from a single call.
    - The tool will delegate to the `BreakpointManager`.
    - Return the status of each breakpoint (e.g., ID, verified, message).

### Tests
- Set a line breakpoint via the tool.
- Set a conditional breakpoint via the tool.
- Remove a breakpoint by ID.
- List all active breakpoints.
- Test error handling for invalid locations or conditions.

**Acceptance**: Breakpoints can be fully managed via the MCP tool.

---

## Task 2.7: MCP Tool - debugger_step

**Time**: 4 hours

### Description
Create the MCP tool for controlling execution flow.

### Subtasks
1.  **Implement `DebuggerStepTool.java`**:
    - Implement `stepIn`, `stepOver`, `stepOut`, `continue`, and `pause` operations.
    - The tool should accept a `threadId` (defaulting to the last stopped thread).
    - It should return immediately after requesting the step; the subsequent `debugger/stopped` notification will confirm completion.

### Tests
- Test all five operations via the tool.
- Test error handling (e.g., stepping a thread that is not suspended).

**Acceptance**: All stepping and continuation operations can be invoked via the MCP tool.

---

## Task 2.8: MCP Tool - debugger_threads

**Time**: 4 hours

### Description
Create the MCP tool for listing and managing threads.

### Subtasks
1.  **Implement `DebuggerThreadsTool.java`**:
    - Implement `list`, `summary`, `suspend`, and `resume` operations.
    - The `list` operation should support filtering by thread type (`platform`, `virtual`, `all`).
    - The `summary` operation should provide aggregate counts.
    - `suspend`/`resume` can target a specific thread or all threads.

### Tests
- `list` returns all platform threads by default.
- `list` with `threadType="virtual"` returns only virtual threads.
- `summary` returns correct counts.
- `suspend` and `resume` work on a single thread.

**Acceptance**: Thread listing and control works via MCP, with support for virtual thread filtering.

---

## Task 2.9: MCP Tool - debugger_stacktrace

**Time**: 4 hours

### Description
Create the MCP tool for retrieving stack traces.

### Subtasks
1.  **Implement `DebuggerStackTraceTool.java`**:
    - Accepts `threadId`, `startFrame`, and `maxDepth` parameters.
    - Delegates to the `StackTraceHandler`.
    - Returns a list of `StackFrameInfo` objects.

### Tests
- Get a stack trace for the last stopped thread (no `threadId` provided).
- Get a paginated stack trace.
- Test error handling for an invalid `threadId`.

**Acceptance**: Stack traces can be retrieved via the MCP tool.

---

## Task 2.10: Integration Testing

**Time**: 8 hours

### Description
Create end-to-end tests that simulate a real debugging session.

### Subtasks
1.  **`testFullDebuggingSession()`**:
    - Start session → set breakpoint → hit breakpoint → verify suspension → get stack trace → step over → verify new location → continue → verify resumption.
2.  **`testMultiThreadedDebugging()`**:
    - Set a breakpoint in code executed by multiple threads.
    - Verify that each thread suspends at the breakpoint.
    - Resume threads individually.
3.  **`testConditionalBreakpointWithMock()`**:
    - Set a conditional breakpoint with a condition the mock evaluator will return `true` for. Verify it hits.
    - Set another with a condition the mock will return `false` for. Verify it does not hit.

### Performance Targets
- Breakpoint hit latency < 50ms
- Step operation latency < 100ms
- Stack trace retrieval < 30ms

**Acceptance**: All integration tests pass and performance targets are met.

---

## Phase 2 Completion Checklist

- [ ] All tasks completed
- [ ] Line, conditional (mocked), and exception breakpoints work
- [ ] All stepping operations work
- [ ] Stack trace retrieval works
- [ ] All corresponding MCP tools are implemented and functional
- [ ] Unit tests passing (>80% coverage)
- [ ] Integration tests passing
- [ ] Performance targets met
- [ ] Code review completed
- [ ] Documentation updated

---

**End of Phase 2 Task Document**

---

## CORRECTIONS AND ADDITIONS (Applied During Planning Review)

### All MCP Tools (Tasks 2.6, 2.7, 2.8, 2.9) - Error Code Propagation
All tool implementations must map exceptions to `DebuggerErrorCode` for consistent error handling:

```java
// Pattern for all debugger tools
@Override
public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Tool operation logic
            Object result = performOperation(arguments);
            return ToolResponse.success(objectMapper.writeValueAsString(result));
        } catch (DebuggerException e) {
            // DebuggerException already has error code
            return ToolResponse.error(e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            // Map generic exceptions to error codes
            DebuggerErrorCode code = categorizeException(e);
            return ToolResponse.error(code.getCode(), e.getMessage());
        }
    }, debuggerExecutor);
}

// Helper method for error categorization
private DebuggerErrorCode categorizeException(Exception e) {
    if (e instanceof VMDisconnectedException) {
        return DebuggerErrorCode.SESSION_NOT_ACTIVE;
    } else if (e instanceof InvalidStackFrameException) {
        return DebuggerErrorCode.THREAD_NOT_SUSPENDED;
    } else if (e instanceof ClassNotLoadedException) {
        return DebuggerErrorCode.BREAKPOINT_INVALID_LOCATION;
    }
    return DebuggerErrorCode.UNKNOWN_ERROR;
}
```

**Rationale**: Consistent error codes enable better error handling in MCP clients and provide actionable error messages.

### Task 2.8: debugger_threads Tool - Virtual Thread Filtering
Enhance the `list` operation with explicit virtual thread handling:

**Tool Schema Addition**:
```json
{
  "include_virtual_threads": {
    "type": "boolean",
    "description": "Include virtual threads in listing (can be millions). Default: false",
    "default": false
  },
  "thread_type_filter": {
    "type": "string",
    "enum": ["platform", "virtual", "all"],
    "description": "Filter by thread type. Default: 'platform'",
    "default": "platform"
  }
}
```

**Implementation Pattern**:
```java
public List<ThreadInfo> listThreads(boolean includeVirtual) {
    List<ThreadReference> allThreads = vm.allThreads();
    
    return allThreads.stream()
        .filter(t -> includeVirtual || !t.isVirtual())  // JDK 21+
        .map(this::convertToThreadInfo)
        .toList();  // Use .toList() not .collect(Collectors.toList())
}
```

**Important Notes**:
- Virtual threads can number in the millions in production applications
- Listing all virtual threads can cause OOM errors or extreme latency
- Default behavior should be `include_virtual_threads=false` for safety
- Warn users when they request virtual threads: "Warning: Found 1.2M virtual threads. Consider using filters."
- `ThreadInfo.isVirtual()` field (added in Phase 1) enables client-side filtering

### Code Style: Stream Collection
When converting streams to lists, use `.toList()` (Java 16+) instead of `.collect(Collectors.toList())`:

```java
// CORRECT (matches project style)
return frames.stream()
    .map(this::convertFrame)
    .toList();

// AVOID (verbose)
return frames.stream()
    .map(this::convertFrame)
    .collect(Collectors.toList());
```

**Rationale**: Project uses JDK 16+ features (records, text blocks). The `.toList()` method is more concise and idiomatic for modern Java.

### Summary of Phase 2 Corrections:

1. ✅ **All Tools**: Error code propagation pattern documented
2. ✅ **Task 2.8**: Virtual thread filtering specified with safe defaults
3. ✅ **Code Style**: `.toList()` pattern documented (no instances found needing correction in this file)
4. ⚠️  **Performance**: Added warning about virtual thread enumeration risks

**Implementation Note**: Apply these patterns during Phase 2 implementation. The error handling pattern should be used consistently across all debugger tools.

---

**End of Phase 2 Task Document with Corrections**
