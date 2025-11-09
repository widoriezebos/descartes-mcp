# Phase 5: Advanced Features

**Timeline**: Week 7
**Status**: Not Started
**Priority**: P1 (High)
**Dependencies**: Phase 4 Complete

---

## Overview

Implement advanced debugging features:
- Hot reload integration with breakpoint migration
- Method breakpoints
- Data breakpoints (watchpoints)
- Logpoints with message formatting
- Hit count filtering

**Success Criteria**:
- Hot reload preserves and migrates breakpoints reliably.
- Method breakpoints trigger on entry.
- Data breakpoints catch field changes.
- Logpoints output without stopping execution.
- Hit count filtering works correctly.

> **Threading Note**: All debugger logic, especially event handling and JDI interactions, must continue to run on the single-threaded `debuggerExecutor` to ensure state consistency and prevent race conditions.

---

## Task 5.1: Hot Reload Integration

**Time**: 10 hours

### Description
Integrate the debugger with the existing `HotReloadService`. This task implements the consumer side of the hot-reload notification contract that was **defined in Phase 1**. The primary goal is to migrate active breakpoints to their new locations after a class has been redefined.

### Breakpoint Migration Logic
```java
public final class HotReloadBreakpointMigration implements AutoCloseable {
    private final HotReloadService hotReloadService;
    private final BreakpointManager breakpointManager;
    private final ExpressionEvaluationModule expressionEvaluationModule;
    private final Executor debuggerExecutor;
    private AutoCloseable subscription;

    public void initialize() {
        subscription = hotReloadService.getEventBus()
            .subscribe(HotReloadCompletionEvent.class, event ->
                CompletableFuture.runAsync(() -> migrateBreakpoints(event), debuggerExecutor)
            );
    }

    private void migrateBreakpoints(HotReloadCompletionEvent event) {
        List<String> changedClasses = event.reloadedClassNames();

        expressionEvaluationModule.clearCacheForClasses(changedClasses);
        breakpointManager.reinstallBreakpointsForClasses(changedClasses, migration -> {
            if (!migration.locationValid()) {
                sendBreakpointMigrated(migration.breakpointId(),
                                       migration.previousLine(),
                                       -1,
                                       false);
            } else {
                sendBreakpointMigrated(migration.breakpointId(),
                                       migration.previousLine(),
                                       migration.newLine(),
                                       true);
            }
        });
    }

    @Override
    public void close() throws Exception {
        if (subscription != null) subscription.close();
    }
}
```

Inside `BreakpointManager`:
```java
public void reinstallBreakpointsForClasses(List<String> classNames,
                                           Consumer<BreakpointMigrationResult> listener) {
    List<BreakpointSpec> specs = snapshotSpecsForClasses(classNames);
    removeBreakpointsByClass(classNames); // disposes old JDI requests safely

    for (BreakpointSpec spec : specs) {
        install(spec).thenAccept(result -> {
            boolean valid = result.jdiRequests().stream()
                .allMatch(req -> ((BreakpointRequest) req).location().codeIndex() != -1);
            if (!valid) {
                markBreakpointUnverified(result.id(), "Location is no longer executable after hot reload.");
            }
            listener.accept(new BreakpointMigrationResult(result.id(),
                                                          spec.lineNumber(),
                                                          result.lineNumber(),
                                                          valid));
        });
    }
}
```

### Notes on Re-verification
- After a class is redefined, line numbers may move or target non-executable bytecode; `codeIndex() != -1` remains the guard for validity.
- Migration work stays on the single-threaded debugger executor to preserve ordering with other JDI activity.
- `BreakpointSpec` storage must capture all information required to rebuild the breakpoint (conditions, logpoints, hit counts, etc.).

### Tests
- A breakpoint is correctly migrated when lines are inserted before it.
- A breakpoint is marked as unverified if its line is removed or becomes non-executable.
- A conditional breakpoint's compiled expression is correctly invalidated and re-compiled on the next hit after a hot reload.

**Acceptance**: Breakpoints are correctly and safely migrated after a hot reload, invalidated breakpoints are marked, and migration notifications include per-breakpoint outcomes.

---

## Task 5.2: Method Breakpoints

**Time**: 6 hours

### Implement MethodBreakpoint
```java
public class MethodBreakpoint implements IBreakpoint {
    // ... fields for className, methodName, signature, etc.

    @Override
    public CompletableFuture<IBreakpoint> install() {
        return CompletableFuture.supplyAsync(() -> {
            // Find the class and method(s)
            ReferenceType refType = vm.classesByName(className).stream().findFirst().orElse(null);
            if (refType == null) { /* handle deferred */ return this; }

            List<Method> methods = (methodSignature != null)
                ? refType.methodsByName(methodName, methodSignature)
                : refType.methodsByName(methodName);

            for (Method method : methods) {
                MethodEntryRequest request = vm.eventRequestManager().createMethodEntryRequest();
                request.addClassFilter(refType);
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                if (hitCount > 0) request.addCountFilter(hitCount);
                request.enable();
                jdiRequests.add(request);
            }

            // Subscribe to MethodEntryEvent and filter for our specific method
            // ...
            return this;
        }, debuggerExecutor);
    }

    private void handleMethodEntry(DebugEvent event) {
        // Runs on debuggerExecutor
        MethodEntryEvent mee = (MethodEntryEvent) event.getEvent();
        // Evaluate condition if present, then decide whether to suspend or resume.
    }
}
```

### Tests
- Break on method entry by name.
- Differentiate overloaded methods using a signature.
- Conditional method breakpoint.

**Acceptance**: Method breakpoints trigger correctly on method entry.

---

## Task 5.3: Data Breakpoints (Watchpoints)

**Time**: 6 hours

### Implement Watchpoint
```java
public class Watchpoint implements IBreakpoint {
    // ... fields for className, fieldName, accessType, etc.

    @Override
    public CompletableFuture<IBreakpoint> install() {
        return CompletableFuture.supplyAsync(() -> {
            ReferenceType refType = vm.classesByName(className).stream().findFirst().orElse(null);
            if (refType == null) { /* handle deferred */ return this; }

            Field field = refType.fieldByName(fieldName);
            if (field == null) { /* handle error */ return this; }

            if ("read".equals(accessType) || "readWrite".equals(accessType)) {
                AccessWatchpointRequest req = vm.eventRequestManager().createAccessWatchpointRequest(field);
                req.enable();
                jdiRequests.add(req);
            }
            if ("write".equals(accessType) || "readWrite".equals(accessType)) {
                ModificationWatchpointRequest req = vm.eventRequestManager().createModificationWatchpointRequest(field);
                req.enable();
                jdiRequests.add(req);
            }
            
            // Subscribe to AccessWatchpointEvent and ModificationWatchpointEvent
            // ...
            return this;
        }, debuggerExecutor);
    }
}
```

### Tests
- Break on field read.
- Break on field write.
- Conditional watchpoint.

**Acceptance**: Data breakpoints correctly trigger on field access/modification.

---

## Task 5.4: Logpoints

**Time**: 6 hours

### Description
Implement logpoints, which are non-suspending breakpoints that evaluate an expression and log the result.

### Subtasks
1.  **Implement `LogpointFormatter.java`**:
    - A class responsible for parsing a log message template (e.g., `"User {user.getName()} logged in"`).
    - It will find all `{expression}` placeholders.
    - For each placeholder, it will use the `IEvaluationProvider` to evaluate the expression.
    - It returns a `CompletableFuture<String>` with the fully formatted message.
2.  **Update Breakpoint Handling Logic**:
    - When a breakpoint with a `logMessage` is hit, it should **not** suspend the thread.
    - Instead, it should invoke the `LogpointFormatter`.
    - Once the formatted string is ready, it sends a `debugger/output` MCP notification.
    - **Crucially**, it must then resume the event set/thread. This entire process must be asynchronous and execute on the `debuggerExecutor`.

### Tests
- A logpoint with a simple variable: `"x = {x}"`.
- A logpoint with a method call: `"Size: {list.size()}"`.
- Verify that execution does not stop at the logpoint.
- Test error handling for expressions that fail to evaluate.

**Acceptance**: Logpoints output formatted messages to the client without suspending execution.

---

## Task 5.5: Hit Count Filtering

**Time**: 4 hours

### Description
Implement support for breakpoints that only trigger on the Nth hit.

### Subtasks
1.  **Leverage JDI `addCountFilter(n)`**:
    - The JDI `EventRequest` has built-in support for hit count filtering. When a count filter is set, the JDI will only send an event on the Nth hit.
    - Update the breakpoint installation logic to call `request.addCountFilter(hitCount)` if a hit count is specified.
2.  **Manual Reset**:
    - A JDI request with a count filter is automatically disabled after it hits. To make it break again after another N hits, the request must be re-enabled.
    - The `BreakpointManager` must handle the `BreakpointEvent`, re-enable the request, and potentially track the hit count manually for UI purposes if needed.

### Tests
- A breakpoint with a hit count of 5 only stops on the 5th time it is reached.
- The hit count resets if the breakpoint is modified.

**Acceptance**: Hit count conditions are respected.

---

## Task 5.6: MCP Tool - debugger_hotreload

**Time**: 4 hours

### Description
Create the MCP tool to trigger and report on hot code replacement.

### Subtasks
1.  **Implement `DebuggerHotReloadTool.java`**:
    - Input: `{"classes": ["com.example.MyClass"]}` (or empty for all changed classes detected by the build system).
    - This tool will invoke the `HotReloadService`.
    - It will wait for the corresponding `HotReloadCompletionEvent` and use its payload to construct the response.
    - Response:
      ```json
      {
        "success": true,
        "reloadedClasses": ["com.example.MyClass"],
        "migratedBreakpoints": [
          {"id": "bp-1", "oldLine": 42, "newLine": 45, "verified": true}
        ],
        "unverifiedBreakpoints": [
          {"id": "bp-2", "reason": "Method no longer exists."}
        ]
      }
      ```

### Tests
- Trigger a hot reload via the tool and verify the response is correct.
- Handle reload failures gracefully.

**Acceptance**: The hot reload process can be managed via the MCP tool.

---

## Task 5.7: Integration Testing

**Time**: 6 hours

### Description
Create end-to-end tests for all advanced features.

### Subtasks
- **`testHotReloadWithBreakpoints()`**: Set a breakpoint, modify the source file to shift the line number, trigger hot reload, and verify the breakpoint is migrated and still functional.
- **`testMethodBreakpoint()`**: Set a breakpoint on a method and verify the debugger stops at the method's entry point.
- **`testWatchpoint()`**: Set a watchpoint on a field, modify the field, and verify the debugger stops at the line causing the modification.
- **`testLogpoint()`**: Set a logpoint, trigger the code, and verify the correct message is received via an MCP notification without the thread suspending.

**Acceptance**: All advanced features are tested and work correctly in an integrated environment.

---

## Task 5.8: Virtual Thread (Project Loom) Support

**Time**: 6 hours

**Dependencies**: Task 2.8 (MCP Tool - debugger_threads)

### Rationale

Java 21 introduces virtual threads as a first-class feature. Debugging services must handle potentially tens of thousands of virtual threads efficiently while keeping the AI agent’s workflow manageable.

### Subtasks

1. **Augment ThreadInfo Model**
   - Add `boolean virtual` flag to `ThreadInfo`.
   - Ensure the builder/constructor and serialization logic include the new field.

2. **Enhance `DebuggerService.listThreads()`**
   - Populate the `virtual` flag using `ThreadReference.isVirtual()` (available JDK 19+).
   - Preserve existing suspended-reason logic.

3. **Extend `DebuggerThreadsTool`**
   - Input schema: add optional `threadType` enum (`"platform"`, `"virtual"`, `"all"`), default `"platform"`.
   - Filter logic: when listing, return only platform threads unless `threadType` requests others.
   - Introduce new `summary` operation returning counts (e.g., `{ "platform": 12, "virtual": 15023 }`).

4. **Performance & UX Validation**
   - Create load test scenario with 10k+ virtual threads.
   - Measure `VirtualMachine.allThreads()` latency, `debugger_threads` list, and `summary` operations.
   - Confirm default behavior (no filter) yields concise platform-thread list.

5. **Documentation**
   - Update tool docs (`debugger_threads`) with `threadType` parameter and `summary` operation usage.
   - Provide examples guiding the AI on when to request virtual threads vs. summary.

### Acceptance Criteria

- [ ] `ThreadInfo` exposes `virtual` flag and `DebuggerService` populates it.
- [ ] `debugger_threads` `list` defaults to platform threads; filter works for virtual/all.
- [ ] `summary` operation returns platform/virtual counts quickly.
- [ ] Performance meets expectations in high-virtual-thread scenarios.
- [ ] Documentation updated with filter + summary guidance.

---

## Phase 5 Completion Checklist

- [ ] All tasks completed
- [ ] Hot reload integration works, including re-verification of breakpoint locations.
- [ ] Method and data breakpoints are functional.
- [ ] Logpoints and hit counts work as specified.
- [ ] All features are exposed via MCP tools.
- [ ] Unit and integration tests pass.
- [ ] Code review completed.
- [ ] Documentation updated.

---

**End of Phase 5 Task Document**
