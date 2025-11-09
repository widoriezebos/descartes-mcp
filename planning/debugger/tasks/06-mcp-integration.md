# Phase 6: MCP Integration Polish

**Timeline**: Week 8
**Status**: Not Started
**Priority**: P1 (High)
**Dependencies**: Phase 5 Complete

---

## Overview

This phase focuses on polishing the MCP integration to ensure the debugger is robust, well-documented, and provides a seamless experience for the AI agent.
- Implement a comprehensive event notification system.
- Standardize error codes and messages.
- Create detailed documentation for all tools and common workflows.
- Integrate all debugger components into the main server example.
- Run and document performance benchmarks.

**Success Criteria**:
- All debug events are reliably sent as rich, actionable MCP notifications.
- Error responses are clear, consistent, and helpful.
- Documentation is complete and accurate.
- Performance targets are met or exceeded.
- An AI agent like Claude can use the tools to perform complex debugging tasks.

---

## Task 6.1: Event Notification System

**Time**: 8 hours

### Description
Implement the `MCPEventBridge`, a component responsible for listening to JDI events from the `EventHub` and translating them into MCP notifications for the client via the `MCPNotificationDispatcher`.

### Implement MCPEventBridge
```java
public class MCPEventBridge {
    private final MCPNotificationDispatcher dispatcher;
    private final EventHub eventHub;
    private final DebuggerService debuggerService;
    private final Executor debuggerExecutor;
    private final List<Disposable> subscriptions = new ArrayList<>();

    public MCPEventBridge(MCPNotificationDispatcher dispatcher,
                          EventHub eventHub,
                          DebuggerService debuggerService) {
        this.dispatcher = dispatcher;
        this.eventHub = eventHub;
        this.debuggerService = debuggerService;
        this.debuggerExecutor = debuggerService.getExecutor();
    }

    public void start() {
        // All event handling MUST be offloaded to the debuggerExecutor
        // to avoid blocking the EventHub's thread and to ensure safe JDI access.
        // Subscriptions are stored for proper cleanup.

        subscriptions.add(
            eventHub.events()
                .ofType(BreakpointEvent.class)
                .observeOn(Schedulers.from(debuggerExecutor))
                .subscribe(e -> sendStoppedNotification("breakpoint", e))
        );

        subscriptions.add(
            eventHub.events()
                .ofType(StepEvent.class)
                .observeOn(Schedulers.from(debuggerExecutor))
                .subscribe(e -> sendStoppedNotification("step", e))
        );

        // ... other event subscriptions (thread start/death, exception, etc.)
    }

    public void stop() {
        subscriptions.forEach(Disposable::dispose);
        subscriptions.clear();
    }

    private void sendStoppedNotification(String reason, LocatableEvent event) {
        // This method is now guaranteed to be running on the debuggerExecutor.
        ThreadReference thread = event.thread();
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason", reason);
            payload.put("threadId", thread.uniqueID());
            payload.put("threadName", thread.name());
            payload.put("timestamp", System.currentTimeMillis());

            if (thread.isSuspended() && thread.frameCount() > 0) {
                StackFrame topFrame = thread.frame(0);
                payload.put("topFrame", formatFrame(topFrame));
                // Enrich the payload with a preview of local variables to reduce chat turns.
                payload.put("locals", formatLocals(topFrame, 5));
                payload.put("stackFrameCount", thread.frameCount());
            }

            dispatcher.sendNotification("descartes/debugger.stopped", payload);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error sending stopped notification", e);
        }
    }

    // ... other formatters and notification senders ...
}
```

### Notification Types
1.  **descartes/debugger.stopped**: Thread suspended. Payload is enriched to be immediately useful, including `threadId`, `reason`, a `topFrame` object (with ID, name, source, line), a `locals` array containing a preview of the first few local variables, and the total `stackFrameCount`.
2.  **descartes/debugger.continued**: Thread resumed.
3.  **descartes/debugger.thread**: Thread lifecycle (started, exited).
4.  **descartes/debugger.breakpoint**: Breakpoint status changed (verified, modified, removed).
5.  **descartes/debugger.output**: Console/log output from logpoints.
6.  **descartes/debugger.hotreload**: Hot reload completed, with status of migrated breakpoints.

### Tests
- Verify each notification type is sent correctly with the expected payload.
- Confirm that all RxJava subscriptions use `observeOn(debuggerExecutor)`.
- Test that calling `stop()` on the bridge disposes of all subscriptions.

**Acceptance**: All debug events emit rich, actionable notifications, and the implementation is thread-safe and leak-free.

---

## Task 6.2: Error Response Standardization

**Time**: 4 hours

### Standard Error Response Format
```java
public class ErrorResponse {
    public static Map<String, Object> create(DebuggerErrorCode errorCode, String details) {
        return Map.of(
            "success", false,
            "error", Map.of(
                "code", errorCode.getCode(),
                "message", errorCode.getMessage(),
                "details", details
            )
        );
    }
}
```

### Actionable Error Messages
- **Bad**: "Failed to set breakpoint"
- **Good**: "Failed to set breakpoint at line 42: Class `com.example.MyClass` not found. The breakpoint will be activated automatically when the class is loaded."

### Error Documentation
Create `docs/debugger-error-codes.md` with detailed explanations and solutions for each error code.

### Tests
- Each error code has a corresponding test case that triggers it.
- Error messages are verified to be actionable and clear.
- Documentation is reviewed for accuracy.

**Acceptance**: Errors are clear, helpful, and consistently formatted.

---

## Task 6.3: Tool Documentation

**Time**: 6 hours

### Description
Create comprehensive documentation for all debugger tools and provide a guide for using them effectively.

### Subtasks
1.  **Create `docs/debugger-tools.md`**:
    - Document every tool (`debugger_session`, `debugger_breakpoints`, etc.).
    - For each tool, provide the full input schema, an example request, and an example response.
    - Clearly explain all parameters.
2.  **Create `docs/claude-debugging-guide.md`**:
    - Provide a high-level guide on how to perform common debugging workflows (e.g., "Debugging a NullPointerException", "Using Hot Reload to Fix a Bug").
    - Use natural language prompts and show the corresponding tool calls and notifications.

### Tests
- All examples in the documentation are tested for correctness.
- The documentation is reviewed for clarity and completeness.

**Acceptance**: All tools are thoroughly documented with practical examples.

---

## Task 6.4: SimpleMCPServerExample Integration

**Time**: 4 hours

### Description
Integrate the complete debugger system into the `SimpleMCPServerExample` to provide a ready-to-use demonstration.

### Update SimpleMCPServerExample
```java
public class SimpleMCPServerExample {
    public static void main(String[] args) {
        // ... existing setup ...
        Map<String, Object> context = new ConcurrentHashMap<>();

        server.addConnectionListener(new ConnectionLifecycleListener() {
            @Override
            public void onOpen(MCPNotificationDispatcher dispatcher) {
                context.put("mcp.dispatcher", dispatcher);
            }

            @Override
            public void onClose() {
                context.remove("mcp.dispatcher");
            }
        });

        // 1. Initialize the single DebuggerService. It encapsulates the executor.
        DebuggerService debuggerService = new DebuggerService(context);

        // 2. Register all debugger tools, injecting only the service.
        //    The tools should not have direct access to the executor.
        server.registerTool(new DebuggerSessionTool(debuggerService));
        server.registerTool(new DebuggerBreakpointsTool(debuggerService));
        server.registerTool(new DebuggerStepTool(debuggerService));
        server.registerTool(new DebuggerThreadsTool(debuggerService));
        server.registerTool(new DebuggerStackTraceTool(debuggerService));
        server.registerTool(new DebuggerVariablesTool(debuggerService));
        server.registerTool(new DebuggerEvaluateTool(debuggerService));
        server.registerTool(new DebuggerWatchTool(debuggerService));
        server.registerTool(new DebuggerExceptionsTool(debuggerService));
        server.registerTool(new DebuggerHotReloadTool(debuggerService));

        // ... start server ...

        System.out.println("Debugger tools registered: 10");
        System.out.println("To start debugging, ask the agent to use the 'debugger_session' tool.");
    }
}
```

### README Updates
Update the main `README.md` to highlight the new debugging capabilities and point to the documentation for a quick start.

### Tests
- The `SimpleMCPServerExample` starts correctly with all debugger tools registered.
- A user can connect and successfully start a debug session.
- The tools correctly delegate their work to the `DebuggerService`.

**Acceptance**: The debugger is fully and correctly integrated into the example server, following good encapsulation principles.

---

## Task 6.5: Performance Benchmarks

**Time**: 6 hours

### Description
Create and run a suite of performance tests to ensure the debugger meets its performance targets.

### Subtasks
1.  **Create `DebuggerPerformanceBenchmarks.java`**:
    - Write tests to measure the latency of critical operations:
        - Breakpoint hit-to-notification time.
        - Variable inspection (for a frame with 100 variables).
        - Step operation completion.
        - Simple and complex expression evaluation.
        - Stack trace retrieval (50 frames).
        - Hot reload with breakpoint migration.
2.  **Generate Performance Report**:
    - Document the results in `docs/performance-report.md`, comparing actual measurements against targets.

### Tests
- All benchmark tests run as part of the build.
- The performance report is automatically generated or updated.

**Acceptance**: Performance is measured, documented, and meets all specified targets.

---

## Task 6.6: Claude Integration Testing

**Time**: 4 hours

### Description
Perform manual, scenario-based testing using a real AI agent (like Claude) to ensure the tools are intuitive and effective in practice.

### Manual Testing Checklist
- **Session Management**: "Start/stop a debug session."
- **Breakpoints**: "Set a breakpoint at line 42 in MyClass.java."
- **Stepping**: "Step over this line."
- **Inspection**: "When it stops, show me the local variables."
- **Evaluation**: "What is the value of `user.getName()`?"
- **Hot Reload**: "Hot reload the `MyClass` file."

### Tests
- Run through at least two complete debugging scenarios (e.g., fixing an NPE, investigating a logic error).
- Document any friction points or areas where the tool interactions could be improved.

**Acceptance**: A human tester, role-playing as an AI agent, can complete common debugging workflows smoothly.

---

## Phase 6 Completion Checklist

- [ ] All tasks completed
- [ ] Event notification system is robust and thread-safe.
- [ ] Error responses are standardized and documented.
- [ ] All tools and workflows are documented.
- [ ] The example server is fully integrated.
- [ ] Performance benchmarks are passing.
- [ ] Manual integration testing is complete.
- [ ] Code review completed.

---

**End of Phase 6 Task Document**

---

## NEW TASK 6.4: DebuggerMetrics for Observability

**Time**: 6 hours
**Priority**: P1 (Important for production readiness)

### Description

Implement metrics collection for debugger operations to validate performance targets and provide observability.

### Subtasks

1.  **Create Debugger Metrics.java**:
    ```java
    public class DebuggerMetrics {
        // Operation counters
        private final AtomicLong breakpointHits = new AtomicLong();
        private final AtomicLong stepOperations = new AtomicLong();
        private final AtomicLong evaluations = new AtomicLong();
        private final AtomicLong variableInspections = new AtomicLong();
        
        // Latency histograms
        private final Histogram breakpointLatency = new Histogram(3);  // 3 significant figures
        private final Histogram stepLatency = new Histogram(3);
        private final Histogram evaluationLatency = new Histogram(3);
        private final Histogram variableLatency = new Histogram(3);
        
        // Cache statistics
        private final AtomicLong expressionCacheHits = new AtomicLong();
        private final AtomicLong expressionCacheMisses = new AtomicLong();
        
        // Record operation with timing
        public void recordBreakpointHit(long latencyMs) {
            breakpointHits.incrementAndGet();
            breakpointLatency.recordValue(latencyMs);
        }
        
        public void recordStepOperation(long latencyMs) {
            stepOperations.incrementAndGet();
            stepLatency.recordValue(latencyMs);
        }
        
        public void recordEvaluation(long latencyMs, boolean cacheHit) {
            evaluations.incrementAndGet();
            evaluationLatency.recordValue(latencyMs);
            if (cacheHit) {
                expressionCacheHits.incrementAndGet();
            } else {
                expressionCacheMisses.incrementAndGet();
            }
        }
        
        // Get metrics snapshot
        public Map<String, Object> toMap() {
            return Map.of(
                "operations", Map.of(
                    "breakpoint_hits", breakpointHits.get(),
                    "step_operations", stepOperations.get(),
                    "evaluations", evaluations.get(),
                    "variable_inspections", variableInspections.get()
                ),
                "latency_ms", Map.of(
                    "breakpoint_p50", breakpointLatency.getValueAtPercentile(50),
                    "breakpoint_p95", breakpointLatency.getValueAtPercentile(95),
                    "breakpoint_p99", breakpointLatency.getValueAtPercentile(99),
                    "step_p50", stepLatency.getValueAtPercentile(50),
                    "step_p95", stepLatency.getValueAtPercentile(95),
                    "evaluation_p50", evaluationLatency.getValueAtPercentile(50),
                    "evaluation_p95", evaluationLatency.getValueAtPercentile(95)
                ),
                "cache", Map.of(
                    "expression_hits", expressionCacheHits.get(),
                    "expression_misses", expressionCacheMisses.get(),
                    "expression_hit_rate", 
                        expressionCacheHits.get() / (double)(expressionCacheHits.get() + expressionCacheMisses.get())
                ),
                "performance_targets", Map.of(
                    "breakpoint_target_ms", 50,
                    "step_target_ms", 100,
                    "variable_target_ms", 100,
                    "breakpoint_meeting_target", breakpointLatency.getValueAtPercentile(95) < 50,
                    "step_meeting_target", stepLatency.getValueAtPercentile(95) < 100
                )
            );
        }
        
        // Reset all metrics (for testing)
        public void reset() {
            breakpointHits.set(0);
            stepOperations.set(0);
            evaluations.set(0);
            variableInspections.set(0);
            breakpointLatency.reset();
            stepLatency.reset();
            evaluationLatency.reset();
            variableLatency.reset();
            expressionCacheHits.set(0);
            expressionCacheMisses.set(0);
        }
    }
    ```

2.  **Integrate with DebuggerService**:
    ```java
    public class DebuggerService {
        private final DebuggerMetrics metrics = new DebuggerMetrics();
        
        // Expose metrics via context
        public DebuggerService(Map<String, Object> context) {
            // ...
            context.put("debuggerMetrics", metrics);
        }
        
        // Example instrumentation in breakpoint handling
        private void handleBreakpointEvent(BreakpointEvent event) {
            long startTime = System.currentTimeMillis();
            try {
                // ... breakpoint handling logic
            } finally {
                long latency = System.currentTimeMillis() - startTime;
                metrics.recordBreakpointHit(latency);
            }
        }
    }
    ```

3.  **Expose via MetricsResource**:
    ```java
    // In MetricsResource.java (existing)
    public String getMetrics() {
        Map<String, Object> allMetrics = new HashMap<>();
        allMetrics.put("system", systemMetrics());
        allMetrics.put("jvm", jvmMetrics());
        
        // Add debugger metrics if available
        Object debuggerMetrics = context.get("debuggerMetrics");
        if (debuggerMetrics instanceof DebuggerMetrics dm) {
            allMetrics.put("debugger", dm.toMap());
        }
        
        return objectMapper.writeValueAsString(allMetrics);
    }
    ```

4.  **Create DebuggerMetricsTool** (optional MCP tool):
    ```java
    public class DebuggerMetricsTool implements MCPTool {
        @Override
        public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
            String operation = (String) arguments.get("operation");
            
            return switch (operation) {
                case "get" -> getMetrics();
                case "reset" -> resetMetrics();
                case "validate_targets" -> validatePerformanceTargets();
                default -> throw new IllegalArgumentException("Unknown operation: " + operation);
            };
        }
        
        private CompletableFuture<ToolResponse> validatePerformanceTargets() {
            // Check if performance targets are being met
            Map<String, Object> metrics = debuggerMetrics.toMap();
            Map<String, Object> latency = (Map<String, Object>) metrics.get("latency_ms");
            
            boolean allTargetsMet = 
                (long) latency.get("breakpoint_p95") < 50 &&
                (long) latency.get("step_p95") < 100 &&
                (long) latency.get("evaluation_p95") < 1000;
            
            return CompletableFuture.completedFuture(
                ToolResponse.success(objectMapper.writeValueAsString(Map.of(
                    "targets_met", allTargetsMet,
                    "details", latency
                )))
            );
        }
    }
    ```

### Tests

- Test metric recording for each operation type
- Verify latency histograms calculate percentiles correctly
- Test metrics reset functionality
- Integration test: Run debugging session and validate metrics

### Acceptance Criteria

- [ ] DebuggerMetrics tracks all operation types and latencies
- [ ] Metrics exposed via MetricsResource
- [ ] Performance targets automatically validated
- [ ] Metrics can be reset for testing
- [ ] No performance overhead from metrics collection (<1% impact)

### Notes

- Use HdrHistogram library (already used in profiler) for latency tracking
- Metrics help validate Phase 2 performance targets (<50ms breakpoint, <100ms step)
- Essential for performance regression testing in Phase 7

---

**Updated Phase 6 Estimated Hours**: 36 → 42 hours (added 6 hours for metrics)

---

**End of Phase 6 Task Document with Additions**
