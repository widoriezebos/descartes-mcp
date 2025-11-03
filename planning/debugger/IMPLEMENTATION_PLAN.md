# Debugger Implementation Plan - Full Autonomous Execution

**Status**: Ready for Implementation
**Total Effort**: ~408 hours (10+ weeks)
**Last Updated**: 2025-11-03
**Planning Documents**: Complete (23 corrections applied)

---

## Execution Configuration

**Mode**: Continuous autonomous execution, stopping only for blocking issues
**Scope**: All 7 phases + existing tool migration
**Testing Strategy**: After each phase completion
**Decision Making**: Make reasonable decisions based on existing patterns, document in code

---

## Prerequisites Checklist

Before starting implementation:

- [ ] All planning documents reviewed (vision.md + 7 phase task files)
- [ ] All 23 corrections understood (see CORRECTIONS_SUMMARY.md)
- [ ] JDK 11+ available for testing
- [ ] JDK 17+ available for JPMS testing
- [ ] Understanding of existing codebase (ProfilerService, HotReloadService patterns)
- [ ] Git working directory clean
- [ ] Ready for breaking MCPTool interface change

---

## Phase 0: Pre-Implementation Setup

**Estimated Time**: 4 hours
**Priority**: P0 (Blocking - must complete before Phase 1)

### Tasks

#### 0.1: Add Dependencies to pom.xml

```xml
<!-- Add to dependencies section -->

<!-- RxJava for reactive event processing -->
<dependency>
  <groupId>io.reactivex.rxjava3</groupId>
  <artifactId>rxjava</artifactId>
  <version>3.1.8</version>
</dependency>

<!-- Janino for expression compilation -->
<dependency>
  <groupId>org.codehaus.janino</groupId>
  <artifactId>janino</artifactId>
  <version>3.1.11</version>
</dependency>
```

#### 0.2: Update README.md

Add debugger requirements section (already drafted, verify placement):
- JDK 11+ minimum requirement
- JVM flags for JDK 11-16 vs JDK 17+
- Security warnings (JDWP + expression evaluation)
- Safe usage guidelines

#### 0.3: Prepare Test File Structure

```bash
# Move ExpressionParserTest.java temporarily
mkdir -p src/test/resources/planning
mv src/test/java/com/bitsapplied/descartes/debugger/expression/parser/ExpressionParserTest.java \
   src/test/resources/planning/

# Create placeholder directories
mkdir -p src/main/java/com/bitsapplied/descartes/debugger
mkdir -p src/test/java/com/bitsapplied/descartes/debugger
```

#### 0.4: Validate Build

```bash
mvn clean compile
# Should succeed with new dependencies
```

### Acceptance Criteria

- [ ] RxJava 3.1.8 and Janino 3.1.11 in pom.xml
- [ ] Build completes successfully
- [ ] README.md updated with debugger section
- [ ] ExpressionParserTest.java moved to resources
- [ ] No compilation errors

---

## Phase 1a: MCPTool Breaking Change Migration

**Estimated Time**: 20-30 hours
**Priority**: P0 (Blocking - required before debugger tools)
**Note**: This is a breaking change affecting all existing tools

### Tasks

#### 1a.1: Update MCPTool Interface

**File**: `src/main/java/com/bitsapplied/descartes/tools/MCPTool.java`

```java
public interface MCPTool extends AutoCloseable {
    String getToolName();
    String getToolDescription();
    Map<String, Object> getToolSchema();

    // NEW: Async execution
    CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments);

    default void close() throws Exception {}
}
```

#### 1a.2: Create ToolResponse Class

**File**: `src/main/java/com/bitsapplied/descartes/tools/ToolResponse.java`

```java
public sealed interface ToolResponse permits ToolResponse.Success, ToolResponse.Error {

    record Success(String content, Map<String, Object> metadata) implements ToolResponse {
        public Success(String content) {
            this(content, Map.of());
        }
    }

    record Error(int code, String message, String details) implements ToolResponse {
        public Error(DebuggerErrorCode errorCode, String details) {
            this(errorCode.getCode(), errorCode.getMessage(), details);
        }
    }

    static ToolResponse success(String content) {
        return new Success(content);
    }

    static ToolResponse error(int code, String message) {
        return new Error(code, message, "");
    }
}
```

#### 1a.3: Create ToolExecutionException

**File**: `src/main/java/com/bitsapplied/descartes/tools/ToolExecutionException.java`

```java
public class ToolExecutionException extends RuntimeException {
    private final int errorCode;

    public ToolExecutionException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ToolExecutionException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}
```

#### 1a.4: Update MCPServer for Async Execution

**File**: `src/main/java/com/bitsapplied/descartes/MCPServer.java`

Changes needed:
1. Handle `CompletableFuture<ToolResponse>` from tools
2. Add dedicated single-threaded writer executor
3. Process tool responses asynchronously
4. Map exceptions to JSON-RPC error format
5. Expose `ConnectionLifecycleListener`

#### 1a.5: Create MCPNotificationDispatcher

**File**: `src/main/java/com/bitsapplied/descartes/mcp/MCPNotificationDispatcher.java`

```java
public final class MCPNotificationDispatcher implements Closeable {
    private final OutputStream outputStream;
    private final ExecutorService notificationExecutor;
    private final BlockingQueue<Notification> notificationQueue;

    public void sendNotification(String method, Map<String, Object> params) {
        // Queue notification for async delivery
    }

    public void sendMessage(String text) {
        // Convenience method for notifications/message
    }

    @Override
    public void close() {
        // Clean shutdown
    }
}
```

#### 1a.6: Migrate 16 Existing Tools

Apply this pattern to all existing tools:

```java
// Example: JShellTool migration
@Override
public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            // Existing synchronous logic
            String operation = getRequiredParam(arguments, "operation", String.class);
            String result = switch (operation) {
                case "execute" -> executeCode(arguments);
                case "reset" -> resetSession();
                // ... other operations
                default -> throw new IllegalArgumentException("Unknown operation: " + operation);
            };
            return ToolResponse.success(result);
        } catch (DebuggerException e) {
            return ToolResponse.error(e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            return ToolResponse.error(9999, "Tool execution failed: " + e.getMessage());
        }
    }, executorService); // Use appropriate executor
}
```

**Tools to migrate** (16 total):
1. JShellTool
2. JShellSessionTool
3. ObjectInspectorTool
4. HotClassReloadTool
5. ProcessInspectorTool
6. SystemMonitoringTool
7. ThreadAnalyzerTool
8. MemoryAnalyzerTool
9. ExceptionAnalysisTool
10. LoggingIntegrationTool
11. ProfilerStartTool
12. ProfilerStopTool
13. ProfilerHotspotsTool
14. ProfilerCallTreeTool
15. ProfilerListTool
16. ProfilerExportTool

### Testing

```bash
# Run all existing tests
mvn test

# Verify SimpleMCPServerExample still works
mvn exec:java

# Test all migrated tools via MCP
# (Manual testing with Claude Desktop or test client)
```

### Acceptance Criteria

- [ ] MCPTool interface updated to async
- [ ] ToolResponse and ToolExecutionException created
- [ ] MCPServer handles async tool execution
- [ ] MCPNotificationDispatcher implemented
- [ ] All 16 existing tools migrated and tested
- [ ] No breaking changes to external MCP protocol
- [ ] SimpleMCPServerExample works

---

## Phase 1b: Debugger Foundation

**Estimated Time**: 60 hours
**Priority**: P0 (Blocking)
**Dependencies**: Phase 1a complete

### Tasks

#### 1b.1: Create Package Structure

```bash
mkdir -p src/main/java/com/bitsapplied/descartes/debugger/{events,breakpoints,threads,models,exceptions,tools}
mkdir -p src/test/java/com/bitsapplied/descartes/debugger
```

Full structure:
```
src/main/java/com/bitsapplied/descartes/debugger/
├── DebuggerService.java
├── DebugSession.java
├── JDWPConnector.java
├── EventHub.java
├── events/
│   ├── DebugEvent.java
│   └── DebugEventType.java
├── breakpoints/
│   ├── IBreakpoint.java
│   ├── BreakpointManager.java
│   └── BreakpointSpec.java
├── threads/
│   ├── ThreadInfo.java
│   ├── ThreadState.java
│   └── ThreadStateManager.java
├── models/
│   ├── StackFrameInfo.java
│   ├── VariableInfo.java
│   ├── DebugSessionConfig.java
│   └── SessionState.java
├── exceptions/
│   ├── DebuggerException.java
│   └── DebuggerErrorCode.java
└── tools/
    ├── DebuggerSessionTool.java
    ├── DebuggerBreakpointsTool.java
    └── DebuggerStepTool.java
```

#### 1b.2: Implement Core Data Models

**ThreadInfo** (with virtual thread support):
```java
public class ThreadInfo {
    private final long id;
    private final String name;
    private final ThreadState state;
    private final String suspendedReason;
    private final Location suspendedLocation;
    private final boolean isVirtual;  // IMPORTANT: Virtual thread flag

    // Constructor, getters, builder
}
```

**SessionState** (state machine):
```java
public enum SessionState {
    CREATED, CONNECTING, READY, SUSPENDED, STEPPING, EVALUATING, DISCONNECTING, CLOSED;

    public boolean canTransitionTo(SessionState target) {
        return VALID_TRANSITIONS.get(this).contains(target);
    }
}
```

**DebuggerErrorCode** (complete enum from planning):
```java
public enum DebuggerErrorCode {
    // Session errors (1000-1099)
    SESSION_NOT_ACTIVE(1000, "No active debug session"),
    SESSION_ALREADY_ACTIVE(1001, "Debug session already active"),
    SESSION_START_FAILED(1002, "Failed to start debug session"),
    JDWP_CONNECTION_FAILED(1003, "Failed to connect to JDWP"),

    // Breakpoint errors (1100-1199)
    BREAKPOINT_SET_FAILED(1100, "Failed to set breakpoint"),
    // ... complete list from planning
}
```

#### 1b.3: Implement JDWPConnector

**Critical requirements**:
1. JDK 11+ version check
2. JDK 17+ JPMS verification (--add-opens check)
3. Circuit breaker for connection resilience
4. Self-attach with port caching
5. Dynamic JDWP enablement

```java
public class JDWPConnector {
    private static final AtomicInteger attachedPort = new AtomicInteger(-1);
    private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static volatile Instant circuitOpenUntil = null;

    public static VirtualMachine attachToSelf(int timeout) throws DebuggerException {
        // 1. JDK 11+ version check
        if (Runtime.version().feature() < 11) {
            throw new DebuggerException(JDWP_CONNECTION_FAILED,
                "Debugger requires JDK 11+ (current: " + Runtime.version() + ")");
        }

        // 2. Circuit breaker check
        if (circuitOpenUntil != null && Instant.now().isBefore(circuitOpenUntil)) {
            throw new DebuggerException(JDWP_CONNECTION_FAILED,
                "Circuit breaker open. Retry in " + /* remaining time */);
        }

        // 3. Check if already attached (caching)
        if (attachedPort.get() != -1) {
            return attachToLocalhost(attachedPort.get(), timeout);
        }

        try {
            // 4. Check for existing JDWP or enable dynamically
            requireSelfAttachEnabled();  // Includes JDK 17+ JPMS check
            int jdwpPort = getExistingJDWPPort();
            if (jdwpPort == -1) {
                jdwpPort = enableJDWP();
            }

            // 5. Cache and connect
            attachedPort.set(jdwpPort);
            VirtualMachine vm = attachToLocalhost(jdwpPort, timeout);

            // Success - reset circuit breaker
            consecutiveFailures.set(0);
            circuitOpenUntil = null;

            return vm;
        } catch (Exception e) {
            // Record failure for circuit breaker
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= 3) {
                circuitOpenUntil = Instant.now().plus(Duration.ofMinutes(5));
            }
            throw new DebuggerException(JDWP_CONNECTION_FAILED, /* ... */, e);
        }
    }

    private static void requireSelfAttachEnabled() {
        // Check 1: Self-attach property
        String allowAttach = System.getProperty("jdk.attach.allowAttachSelf");
        if (!Boolean.parseBoolean(allowAttach)) {
            throw new DebuggerException(JDWP_CONNECTION_FAILED,
                "Self-attach disabled. Use -Djdk.attach.allowAttachSelf=true");
        }

        // Check 2: JDK 17+ JPMS verification
        if (Runtime.version().feature() >= 17) {
            try {
                com.sun.tools.attach.VirtualMachine.list();
                logger.info("JDK 17+ JPMS check passed");
            } catch (IllegalAccessError | InaccessibleObjectException e) {
                throw new DebuggerException(JDWP_CONNECTION_FAILED,
                    "JDK 17+ requires --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED");
            }
        }
    }

    // ... rest of implementation from planning
}
```

#### 1b.4: Implement EventHub with RxJava

**Key requirements**:
1. Dedicated EventHub-Thread (daemon)
2. Synchronous handoff to debuggerExecutor
3. Use `.ofType()` for event filtering (not `.filter()`)

```java
public class EventHub {
    private final Subject<DebugEvent> eventSubject;
    private final ExecutorService debuggerExecutor;
    private Thread eventThread;

    public Observable<DebugEvent> events() {
        return eventSubject;
    }

    // Convenience method using .ofType()
    public <T extends Event> Observable<DebugEvent> eventsOfType(Class<T> eventClass) {
        return eventSubject
            .filter(de -> eventClass.isInstance(de.getEvent()))
            .observeOn(Schedulers.from(debuggerExecutor));
    }

    private void processEvents(VirtualMachine vm) {
        EventQueue eventQueue = vm.eventQueue();
        while (running && !vm.isDisconnected()) {
            EventSet eventSet = eventQueue.remove(); // Blocking

            // Synchronous handoff to debuggerExecutor
            CompletableFuture<Void> processingFuture = CompletableFuture.runAsync(() -> {
                for (Event event : eventSet) {
                    eventSubject.onNext(new DebugEvent(event, eventSet));
                }
            }, debuggerExecutor);

            processingFuture.join();  // Wait for processing

            // Decide whether to resume
            if (eventSet.suspendPolicy() != EventRequest.SUSPEND_ALL) {
                eventSet.resume();
            }
        }
    }
}
```

#### 1b.5: Implement DebuggerService

**Key requirements**:
1. SessionState state machine with transition validation
2. Single-threaded debuggerExecutor
3. Integration with MCPNotificationDispatcher
4. Clean lifecycle management

```java
public class DebuggerService {
    private final Map<String, Object> context;
    private final ExecutorService debuggerExecutor;
    private volatile SessionState state = SessionState.CREATED;
    private DebugSession currentSession;
    private EventHub eventHub;
    private BreakpointManager breakpointManager;
    private ThreadStateManager threadStateManager;

    public DebuggerService(Map<String, Object> context) {
        this.context = context;
        this.debuggerExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "descartes-debugger"));
    }

    public CompletableFuture<DebugSession> startSession(DebugSessionConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            transitionTo(SessionState.CONNECTING);

            try {
                VirtualMachine vm = JDWPConnector.attachToSelf(config.jdwpTimeout());
                currentSession = new DebugSession(vm);

                // Initialize components
                eventHub = new EventHub(debuggerExecutor);
                eventHub.start(vm);
                breakpointManager = new BreakpointManager(vm, eventHub, debuggerExecutor);
                threadStateManager = new ThreadStateManager(vm, eventHub, debuggerExecutor);

                // Get notification dispatcher from context
                MCPNotificationDispatcher dispatcher =
                    (MCPNotificationDispatcher) context.get("mcp.dispatcher");

                transitionTo(SessionState.READY);
                context.put("debugSession", currentSession);

                return currentSession;
            } catch (Exception e) {
                transitionTo(SessionState.CLOSED);
                throw new DebuggerException(SESSION_START_FAILED, /* ... */, e);
            }
        }, debuggerExecutor);
    }

    private synchronized void transitionTo(SessionState newState) {
        state.validateTransition(newState);
        SessionState oldState = state;
        state = newState;
        logger.info("Session state: " + oldState + " -> " + newState);
    }
}
```

#### 1b.6: Implement DebuggerSessionTool

```java
public class DebuggerSessionTool implements MCPTool {
    private final DebuggerService debuggerService;

    @Override
    public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
        String operation = (String) arguments.get("operation");

        return switch (operation) {
            case "start" -> startSession(arguments);
            case "stop" -> stopSession();
            case "status" -> getStatus();
            default -> CompletableFuture.completedFuture(
                ToolResponse.error(400, "Unknown operation: " + operation));
        };
    }

    private CompletableFuture<ToolResponse> startSession(Map<String, Object> args) {
        DebugSessionConfig config = DebugSessionConfig.fromMap(args);
        return debuggerService.startSession(config)
            .thenApply(session -> ToolResponse.success(
                objectMapper.writeValueAsString(Map.of(
                    "sessionId", session.getSessionId(),
                    "status", "active"
                ))
            ))
            .exceptionally(e -> ToolResponse.error(
                categorizeException(e).getCode(),
                e.getMessage()
            ));
    }
}
```

#### 1b.7: Define HotReloadEventBus Contract

**File**: `src/main/java/com/bitsapplied/descartes/hotreload/HotReloadEventBus.java`

```java
public final class HotReloadEventBus implements Closeable {
    private final ExecutorService executor;

    public <T extends HotReloadEvent> AutoCloseable subscribe(
        Class<T> type, Consumer<T> listener) {
        // Implementation
    }

    public void publish(HotReloadEvent event) {
        // Implementation
    }

    @Override
    public void close() {
        // Clean shutdown
    }
}
```

**Integration point for Phase 5**:
```java
// In DebuggerService (Phase 5)
hotReloadService.getEventBus()
    .subscribe(HotReloadCompletionEvent.class, event ->
        CompletableFuture.runAsync(() -> {
            for (String className : event.reloadedClassNames()) {
                expressionCache.invalidateClass(className);
            }
        }, debuggerExecutor)
    );
```

### Testing

```bash
# Run Phase 1b tests
mvn test -Dtest="**/debugger/**/*Test"

# Test with JDK 11
# Test with JDK 17 (with --add-opens)
# Test with JDK 17 (without --add-opens - should fail with clear error)

# Integration test
# - Can start debug session
# - Can list threads in target JVM
# - Can stop debug session cleanly
# - No resource leaks
```

### Acceptance Criteria

- [ ] All core data models implemented
- [ ] JDWPConnector with JDK 11+ and 17+ checks
- [ ] EventHub with RxJava working correctly
- [ ] DebuggerService with SessionState state machine
- [ ] DebuggerSessionTool functional
- [ ] Can start/stop debug session
- [ ] Can list threads
- [ ] Unit tests >80% coverage
- [ ] Integration test passes
- [ ] Works on JDK 11, 17, 21

---

## Phase 2: Core Debugging

**Estimated Time**: 58 hours
**Priority**: P0 (Blocking)
**Dependencies**: Phase 1b complete

### Tasks

#### 2.1: Breakpoint Infrastructure

**Implement**:
1. IBreakpoint interface
2. LineBreakpoint with deferred installation
3. ConditionalBreakpoint with MockEvaluationProvider
4. ExceptionBreakpoint
5. BreakpointManager

**MockEvaluationProvider** (temporary - replaced in Phase 4):
```java
public class MockEvaluationProvider implements IEvaluationProvider {
    @Override
    public CompletableFuture<Value> evaluate(String expression,
                                             ThreadReference thread,
                                             StackFrame frame) {
        return CompletableFuture.supplyAsync(() -> {
            // Simple logic for testing
            if (expression.contains("true")) {
                return frame.virtualMachine().mirrorOf(true);
            } else if (expression.contains("false")) {
                return frame.virtualMachine().mirrorOf(false);
            }
            throw new DebuggerException(EVALUATION_FAILED,
                "Mock evaluator only supports 'true' and 'false'");
        });
    }
}
```

#### 2.2: Stepping Operations

**Implement**:
1. StepHandler (step in, over, out, continue, pause)
2. Step filtering (skip java.*, javax.*, etc.)

#### 2.3: Stack Trace Inspection

**Implement**:
1. StackTraceHandler
2. StackFrameManager (stable frame IDs)

#### 2.4: MCP Tools

**Implement with error code propagation pattern**:

```java
// Pattern for all tools
@Override
public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            Object result = performOperation(arguments);
            return ToolResponse.success(objectMapper.writeValueAsString(result));
        } catch (DebuggerException e) {
            return ToolResponse.error(e.getErrorCode().getCode(), e.getMessage());
        } catch (Exception e) {
            DebuggerErrorCode code = categorizeException(e);
            return ToolResponse.error(code.getCode(), e.getMessage());
        }
    }, debuggerExecutor);
}
```

**Tools to implement**:
1. DebuggerBreakpointsTool (set/remove/list operations)
2. DebuggerStepTool (in/over/out/continue/pause)
3. **DebuggerThreadsTool** with virtual thread filtering:
   ```java
   // Schema
   {
     "include_virtual_threads": {
       "type": "boolean",
       "default": false,
       "description": "Include virtual threads (can be millions)"
     }
   }

   // Implementation
   public List<ThreadInfo> listThreads(boolean includeVirtual) {
       return vm.allThreads().stream()
           .filter(t -> includeVirtual || !t.isVirtual())
           .map(this::convertToThreadInfo)
           .toList();  // Use .toList() not .collect(Collectors.toList())
   }
   ```
4. DebuggerStackTraceTool

### Testing

```bash
# Unit tests
mvn test -Dtest="**/debugger/**/*Test"

# Integration tests
# - Set and hit breakpoint
# - Conditional breakpoint (with mock evaluator)
# - Exception breakpoint
# - All stepping operations
# - Stack trace retrieval
# - Multi-threaded debugging
# - Virtual thread filtering (create millions of virtual threads)

# Performance tests
# - Breakpoint hit latency <50ms (p95)
# - Step operation latency <100ms (p95)
# - Stack trace retrieval <30ms
```

### Acceptance Criteria

- [ ] All breakpoint types working (line, conditional, exception)
- [ ] All stepping operations functional
- [ ] Stack traces retrievable with stable frame IDs
- [ ] All MCP tools implemented with error code propagation
- [ ] Virtual thread filtering working (defaults to exclude)
- [ ] Integration tests pass
- [ ] Performance targets met (<50ms breakpoint, <100ms step)
- [ ] Unit test coverage >80%

---

## Phase 3: Variables

**Estimated Time**: 46 hours
**Priority**: P1 (Important)
**Dependencies**: Phase 2 complete

### Tasks

#### 3.1: Variable Extraction

**Implement**:
1. VariableExtractor with JDI type handling
2. Primitive, object, collection, array handling
3. Depth limiting for object graphs
4. Pagination for collections

#### 3.2: Variable Formatting

**Implement**:
1. VariableFormatter
2. Primitive formatting
3. Collection formatting (with pagination)
4. Object formatting (with depth limiting)
5. Custom toString() handling

#### 3.3: Variable Reference Management

**Implement**:
1. VariableReferencePool (stable IDs for variables)
2. VariableCache with WeakReference
3. Cache invalidation on thread resume

#### 3.4: MCP Tool

**Implement**: DebuggerVariablesTool

Operations:
- Get variables for stack frame
- Expand object references
- Paginate collections

### Testing

```bash
# Unit tests
# - Primitive extraction
# - Object extraction with depth limiting
# - Collection extraction with pagination
# - Array extraction
# - toString() formatting
# - Cache lifecycle

# Integration tests
# - Variable inspection at breakpoint
# - Deep object graphs
# - Large collections
# - Reference stability

# Performance tests
# - Variable inspection <100ms (p95)
```

### Acceptance Criteria

- [ ] Variable extraction for all types working
- [ ] Formatting with depth/pagination working
- [ ] Variable references stable while suspended
- [ ] DebuggerVariablesTool functional
- [ ] Performance target met (<100ms)
- [ ] Unit test coverage >80%

---

## Phase 4: Expression Evaluation

**Estimated Time**: 58 hours
**Priority**: P1 (Important)
**Dependencies**: Phase 3 complete

### Tasks

#### 4.1: Simple Expression Interpreter

**Implement**:
1. ExpressionParser (basic arithmetic, field access, method calls)
2. ExpressionEvaluator (interprets parsed expressions)
3. Handle simple expressions without compilation

#### 4.2: Janino Compiler Integration

**Implement**:
1. **JDI-aware ClassLoader**:
   ```java
   public class JDIClassLoader extends ClassLoader {
       private final VirtualMachine vm;
       private final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();

       @Override
       protected Class<?> findClass(String name) throws ClassNotFoundException {
           byte[] bytecode = bytecodeCache.get(name);
           if (bytecode == null) {
               List<ReferenceType> types = vm.classesByName(name);
               if (types.isEmpty()) {
                   throw new ClassNotFoundException(name);
               }
               bytecode = types.get(0).bytecodes();
               bytecodeCache.put(name, bytecode);
           }
           return defineClass(name, bytecode, 0, bytecode.length);
       }

       public void invalidateCache() {
           bytecodeCache.clear();
       }
   }
   ```

2. **JaninoEvaluator**:
   ```java
   public class JaninoEvaluator implements IEvaluationProvider {
       private final JDIClassLoader classLoader;
       private final ExpressionCache cache;

       @Override
       public CompletableFuture<Value> evaluate(String expression,
                                                ThreadReference thread,
                                                StackFrame frame) {
           return CompletableFuture.supplyAsync(() -> {
               // 1. Extract variable context
               Map<String, LocalVariable> locals = /* ... */;

               // 2. Check cache
               CacheKey key = new CacheKey(expression, locals.keySet(),
                                          /* variable types */);
               CompiledExpression compiled = cache.get(key);

               if (compiled == null) {
                   // 3. Compile with Janino
                   IExpressionEvaluator evaluator = new ExpressionEvaluator();
                   evaluator.setParentClassLoader(classLoader);
                   evaluator.setParameters(/* ... */);
                   evaluator.cook(expression);

                   compiled = new CompiledExpression(evaluator);
                   cache.put(key, compiled);
               }

               // 4. Evaluate
               Object result = compiled.evaluate(/* variable values */);

               // 5. Convert back to JDI Value
               return convertJavaToJDIValue(result, thread.virtualMachine());
           }, debuggerExecutor);
       }
   }
   ```

3. Value conversion methods (JDI ↔ Java)

#### 4.3: Expression Caching

**Implement**:
```java
public class ExpressionCache {
    public record CacheKey(
        String expression,
        Set<String> variableNames,
        List<String> variableTypes
    ) {}

    private final Map<CacheKey, WeakReference<CompiledExpression>> cache =
        new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CompiledExpression get(CacheKey key) {
        WeakReference<CompiledExpression> ref = cache.get(key);
        if (ref != null) {
            CompiledExpression expr = ref.get();
            if (expr != null) {
                hits.incrementAndGet();
                return expr;
            } else {
                cache.remove(key);
            }
        }
        misses.incrementAndGet();
        return null;
    }

    public void invalidateClass(String className) {
        cache.entrySet().removeIf(entry ->
            entry.getKey().variableTypes().stream()
                .anyMatch(type -> type.startsWith(className))
        );
    }
}
```

#### 4.4: JShell Fallback

**Implement**:
```java
public class HybridEvaluationProvider implements IEvaluationProvider {
    private final JaninoEvaluator janinoEvaluator;
    private final JShellEvaluator jshellEvaluator;

    @Override
    public CompletableFuture<Value> evaluate(String expression,
                                             ThreadReference thread,
                                             StackFrame frame) {
        // Try Janino first (faster)
        return janinoEvaluator.evaluate(expression, thread, frame)
            .exceptionally(janinoError -> {
                logger.info("Janino failed, falling back to JShell: " +
                           janinoError.getMessage());

                try {
                    return jshellEvaluator.evaluate(expression, thread, frame).join();
                } catch (Exception jshellError) {
                    throw new DebuggerException(EVALUATION_FAILED,
                        "Both evaluators failed. Janino: " + janinoError.getMessage() +
                        "; JShell: " + jshellError.getMessage());
                }
            });
    }
}
```

#### 4.5: Replace Mock Evaluator

Replace `MockEvaluationProvider` in Phase 2 code with `HybridEvaluationProvider`.

#### 4.6: MCP Tool

**Implement**: DebuggerEvaluateTool

#### 4.7: Restore Test File

```bash
# Move ExpressionParserTest.java back
mv src/test/resources/planning/ExpressionParserTest.java \
   src/test/java/com/bitsapplied/descartes/debugger/expression/parser/
```

### Testing

```bash
# Unit tests
# - Simple expressions (interpreter)
# - Complex expressions (Janino)
# - Very complex expressions (JShell fallback)
# - Cache hit/miss rates
# - Cache invalidation
# - Value conversions

# Integration tests
# - Evaluate at breakpoint
# - Conditional breakpoints with real evaluator
# - Expressions with local variables
# - Expressions with method calls
# - Lambda expressions (JShell)
# - Stream expressions (JShell)

# Performance tests
# - Expression evaluation <1000ms (p95)
# - Cache hit performance <50ms
```

### Acceptance Criteria

- [ ] Simple expression interpreter working
- [ ] Janino compiler integration working
- [ ] JDI-aware classloader working
- [ ] Expression caching with WeakReference
- [ ] JShell fallback working
- [ ] HybridEvaluationProvider replacing MockEvaluationProvider
- [ ] DebuggerEvaluateTool functional
- [ ] Performance target met (<1000ms evaluation)
- [ ] Cache hit rate >50% in typical usage
- [ ] Unit test coverage >80%

---

## Phase 5: Advanced Features

**Estimated Time**: 42 hours
**Priority**: P2 (Nice to have)
**Dependencies**: Phase 4 complete

### Tasks

#### 5.1: Hot Reload Integration

**Implement**:
1. **HotReloadEventBus** in HotReloadService (if not already exists)
2. **Subscribe to HotReloadCompletionEvent** in DebuggerService:
   ```java
   hotReloadService.getEventBus()
       .subscribe(HotReloadCompletionEvent.class, event ->
           CompletableFuture.runAsync(() -> {
               // Invalidate expression cache
               for (String className : event.reloadedClassNames()) {
                   expressionCache.invalidateClass(className);
                   jdiClassLoader.invalidateCache();
               }

               // Migrate breakpoints
               breakpointManager.migrateBreakpoints(event.reloadedClassNames());
           }, debuggerExecutor)
       );
   ```
3. Breakpoint migration logic

#### 5.2: Advanced Breakpoint Types

**Implement**:
1. Method entry/exit breakpoints
2. Data breakpoints (watchpoints - field access/modification)
3. Logpoints (log message without suspending)

#### 5.3: Virtual Thread Support

**Enhance**:
- ThreadInfo already has `isVirtual` field (Phase 1)
- DebuggerThreadsTool already filters (Phase 2)
- Just ensure comprehensive testing with millions of virtual threads

#### 5.4: MCP Tools

**Implement**:
1. DebuggerWatchTool (manage watchpoints)
2. DebuggerHotReloadTool (trigger hot reload with breakpoint migration)

### Testing

```bash
# Integration tests
# - Hot reload with breakpoint migration
# - Method breakpoints
# - Watchpoints (field access/modification)
# - Logpoints
# - Virtual thread stress test (create 1M+ virtual threads)

# Performance tests
# - Hot reload + breakpoint migration <500ms
# - Virtual thread enumeration safety
```

### Acceptance Criteria

- [ ] Hot reload integration working
- [ ] Breakpoint migration on hot reload
- [ ] Expression cache invalidation on hot reload
- [ ] Method breakpoints working
- [ ] Watchpoints working
- [ ] Logpoints working
- [ ] Virtual thread support validated (can handle millions)
- [ ] DebuggerWatchTool functional
- [ ] DebuggerHotReloadTool functional
- [ ] Integration tests pass
- [ ] Unit test coverage >80%

---

## Phase 6: MCP Integration & Observability

**Estimated Time**: 42 hours
**Priority**: P1 (Important)
**Dependencies**: Phase 5 complete

### Tasks

#### 6.1: MCPEventBridge

**Implement**:
```java
public class MCPEventBridge {
    private final MCPNotificationDispatcher dispatcher;
    private final EventHub eventHub;
    private final DebuggerService debuggerService;
    private final ExecutorService debuggerExecutor;

    public void start() {
        // Subscribe to breakpoint events
        eventHub.eventsOfType(BreakpointEvent.class)
            .subscribe(event -> handleBreakpointEvent(event));

        // Subscribe to step events
        eventHub.eventsOfType(StepEvent.class)
            .subscribe(event -> handleStepEvent(event));

        // Subscribe to exception events
        eventHub.eventsOfType(ExceptionEvent.class)
            .subscribe(event -> handleExceptionEvent(event));
    }

    private void handleBreakpointEvent(DebugEvent event) {
        // Enrich with context (locals preview)
        Map<String, Object> payload = enrichBreakpointPayload(event);

        // Send notification
        dispatcher.sendNotification("descartes/debugger.breakpoint", payload);
    }
}
```

#### 6.2: DebuggerMetrics

**Implement**:
```java
public class DebuggerMetrics {
    // Operation counters
    private final AtomicLong breakpointHits = new AtomicLong();
    private final AtomicLong stepOperations = new AtomicLong();
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong variableInspections = new AtomicLong();

    // Latency histograms (use HdrHistogram)
    private final Histogram breakpointLatency = new Histogram(3);
    private final Histogram stepLatency = new Histogram(3);
    private final Histogram evaluationLatency = new Histogram(3);
    private final Histogram variableLatency = new Histogram(3);

    // Cache statistics
    private final AtomicLong expressionCacheHits = new AtomicLong();
    private final AtomicLong expressionCacheMisses = new AtomicLong();

    public void recordBreakpointHit(long latencyMs) {
        breakpointHits.incrementAndGet();
        breakpointLatency.recordValue(latencyMs);
    }

    public Map<String, Object> toMap() {
        return Map.of(
            "operations", Map.of(/* counts */),
            "latency_ms", Map.of(
                "breakpoint_p50", breakpointLatency.getValueAtPercentile(50),
                "breakpoint_p95", breakpointLatency.getValueAtPercentile(95),
                "breakpoint_p99", breakpointLatency.getValueAtPercentile(99),
                // ... other metrics
            ),
            "performance_targets", Map.of(
                "breakpoint_target_ms", 50,
                "breakpoint_meeting_target", breakpointLatency.getValueAtPercentile(95) < 50,
                // ... other targets
            )
        );
    }
}
```

#### 6.3: Integrate with MetricsResource

**Update**: `src/main/java/com/bitsapplied/descartes/resources/MetricsResource.java`

```java
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

#### 6.4: Standardize Error Responses

Ensure all debugger tools use DebuggerErrorCode consistently (should already be done in Phase 2).

#### 6.5: Update SimpleMCPServerExample

**Add debugger registration**:
```java
// In SimpleMCPServerExample.main()
// Create DebuggerService
DebuggerService debuggerService = new DebuggerService(context);
DebuggerMetrics debuggerMetrics = new DebuggerMetrics();
context.put("debuggerMetrics", debuggerMetrics);

// Register debugger tools
server.registerTool(new DebuggerSessionTool(debuggerService));
server.registerTool(new DebuggerBreakpointsTool(debuggerService));
server.registerTool(new DebuggerStepTool(debuggerService));
server.registerTool(new DebuggerThreadsTool(debuggerService));
server.registerTool(new DebuggerStackTraceTool(debuggerService));
server.registerTool(new DebuggerVariablesTool(debuggerService));
server.registerTool(new DebuggerEvaluateTool(debuggerService));
server.registerTool(new DebuggerWatchTool(debuggerService));
server.registerTool(new DebuggerHotReloadTool(debuggerService));
```

### Testing

```bash
# Unit tests
# - MCPEventBridge notification delivery
# - DebuggerMetrics recording and reporting
# - Error response standardization

# Integration tests
# - Notifications received for all event types
# - Metrics accurately track operations
# - Performance targets validated via metrics

# End-to-end test
# - Run SimpleMCPServerExample with debugger
# - Invoke all tools via MCP
# - Verify all functionality working
```

### Acceptance Criteria

- [ ] MCPEventBridge sending notifications for all event types
- [ ] DebuggerMetrics tracking all operations
- [ ] Metrics exposed via MetricsResource
- [ ] Performance targets validated
- [ ] All error responses use DebuggerErrorCode
- [ ] SimpleMCPServerExample includes debugger
- [ ] All tools accessible via MCP
- [ ] Integration tests pass
- [ ] Unit test coverage >80%

---

## Phase 7: Testing & Production Readiness

**Estimated Time**: 78 hours
**Priority**: P0 (Critical for release)
**Dependencies**: Phase 6 complete

### Tasks

#### 7.1: DebuggerTestBase Infrastructure

**Implement**:
```java
public abstract class DebuggerTestBase {

    protected static class TestJVM implements AutoCloseable {
        private final Process process;
        private final int debugPort;

        public static TestJVM launch(Class<?> mainClass, boolean suspend, Duration timeout)
                throws IOException, InterruptedException {
            int port = findFreePort();

            String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");

            ProcessBuilder pb = new ProcessBuilder(
                javaHome + "/bin/java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=" +
                    (suspend ? "y" : "n") + ",address=127.0.0.1:" + port,
                "-cp", classpath,
                mainClass.getName()
            );

            pb.inheritIO();
            Process process = pb.start();

            if (!waitForDebugPort(port, timeout)) {
                process.destroyForcibly();
                throw new IOException("JDWP port " + port + " not available within " + timeout);
            }

            return new TestJVM(process, port);
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        private static int findFreePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        }

        private static boolean waitForDebugPort(int port, Duration timeout) {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    return true;
                } catch (IOException e) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return false;
        }
    }

    protected void withTestJVM(Class<?> mainClass, boolean suspend,
                               TestJVMConsumer test) throws Exception {
        try (TestJVM testJVM = TestJVM.launch(mainClass, suspend, Duration.ofSeconds(10))) {
            test.accept(testJVM);
        }
    }

    @FunctionalInterface
    protected interface TestJVMConsumer {
        void accept(TestJVM testJVM) throws Exception;
    }
}
```

#### 7.2: SimpleTestApplication

**Implement**:
```java
public class SimpleTestApplication {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("SimpleTestApplication started");

        SimpleTestApplication app = new SimpleTestApplication();
        app.countingLoop();
        app.methodCalls();
        app.exceptionScenario();
        app.variableInspection();

        System.out.println("SimpleTestApplication finished");
    }

    public void countingLoop() {
        int counter = 0;
        for (int i = 0; i < 10; i++) {
            counter++;  // BREAKPOINT TARGET: line X
            System.out.println("Counter: " + counter);
        }
    }

    public void methodCalls() {
        int result = add(5, 3);  // STEP-OVER TARGET
        result = multiply(result, 2);  // STEP-INTO TARGET
        System.out.println("Result: " + result);
    }

    private int add(int a, int b) {
        return a + b;  // STEP TARGET
    }

    private int multiply(int a, int b) {
        int result = a * b;  // BREAKPOINT TARGET
        return result;
    }

    public void exceptionScenario() {
        try {
            throwException();
        } catch (IllegalArgumentException e) {
            System.out.println("Caught: " + e.getMessage());
        }
    }

    private void throwException() {
        throw new IllegalArgumentException("Test exception");  // EXCEPTION BREAKPOINT
    }

    public void variableInspection() {
        int primitiveInt = 42;
        String stringVar = "Hello, Debugger!";
        int[] arrayVar = {1, 2, 3, 4, 5};
        Person objectVar = new Person("Alice", 30);

        System.out.println("Variables initialized");  // BREAKPOINT for variable inspection
    }

    static class Person {
        private String name;
        private int age;

        public Person(String name, int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public String toString() {
            return "Person{name='" + name + "', age=" + age + "}";
        }
    }
}
```

#### 7.3: Comprehensive Integration Tests

**Implement**:
1. `FullDebuggingSessionTest` - Complete workflow test
2. `MultiThreadedDebuggingTest` - Thread handling
3. `ConditionalBreakpointTest` - Expression evaluation
4. `HotReloadIntegrationTest` - Breakpoint migration
5. `VirtualThreadTest` - Stress test with millions of threads
6. `PerformanceValidationTest` - All performance targets

#### 7.4: Add Debugger Test Maven Profile

**Add to pom.xml**:
```xml
<profile>
  <id>debugger-tests</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/debugger/**/*Test.java</include>
          </includes>
          <systemPropertyVariables>
            <jdk.attach.allowAttachSelf>true</jdk.attach.allowAttachSelf>
          </systemPropertyVariables>
          <argLine>--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED</argLine>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

#### 7.5: JDK Version Testing

**Test matrix**:
```bash
# JDK 11 (without --add-opens)
mvn test -Pdebugger-tests

# JDK 17 (with --add-opens)
mvn test -Pdebugger-tests

# JDK 17 (without --add-opens - should fail with clear error)
mvn test -Pdebugger-tests \
  -Dexec.args="--add-modules jdk.attach,jdk.jdi"  # Missing --add-opens

# JDK 21 (with --add-opens)
mvn test -Pdebugger-tests

# JDK 23 (with --add-opens)
mvn test -Pdebugger-tests
```

#### 7.6: Performance Validation

**Validate all targets**:
- Breakpoint hit latency <50ms (p95) ✓
- Step operation latency <100ms (p95) ✓
- Variable inspection <100ms (p95) ✓
- Expression evaluation <1000ms (p95) ✓
- Stack trace retrieval <30ms ✓

#### 7.7: Security Review

**Review**:
1. Expression evaluation security (arbitrary code execution)
2. JDWP exposure risks
3. Resource cleanup (no leaks)
4. Input validation
5. Error message information disclosure

#### 7.8: Documentation Review

**Verify**:
- [ ] README.md complete with debugger section
- [ ] All task files accurate
- [ ] CORRECTIONS_SUMMARY.md complete
- [ ] JavaDoc on public APIs
- [ ] Code comments on complex logic
- [ ] Examples in SimpleMCPServerExample

### Testing

```bash
# Run all tests
mvn test

# Run debugger tests only
mvn test -Pdebugger-tests

# Run concurrency tests
mvn test -Pconcurrency-tests

# Run all tests including debugger
mvn test -Pall-tests

# Performance validation
# (DebuggerMetrics automatically validates targets)

# Integration test with SimpleMCPServerExample
mvn exec:java -Djdk.attach.allowAttachSelf=true
```

### Acceptance Criteria

- [ ] DebuggerTestBase fully functional
- [ ] SimpleTestApplication provides all test scenarios
- [ ] All integration tests pass
- [ ] All performance targets met and validated
- [ ] Tests pass on JDK 11, 17, 21
- [ ] JDK 17 without --add-opens fails with clear error message
- [ ] No resource leaks detected
- [ ] Security review complete
- [ ] Documentation complete and accurate
- [ ] SimpleMCPServerExample works end-to-end
- [ ] Unit test coverage >80%
- [ ] Integration test coverage comprehensive

---

## Decision-Making Guidelines

When encountering ambiguities or decisions not covered in planning:

### Reference Implementations

**Use existing patterns from**:
- **ProfilerService**: Session management, metrics collection, tool structure
- **HotReloadService**: Integration patterns, event bus, service lifecycle
- **Existing tools**: Error handling, parameter extraction, JSON serialization
- **Existing tests**: Test structure, integration test patterns

### Code Style

- Use `.toList()` instead of `.collect(Collectors.toList())`
- Use `records` for immutable data models
- Use builder pattern for complex configuration objects
- Follow existing Descartes naming conventions
- Use `var` for local variables when type is obvious
- Use streams for collections processing

### Error Handling

- Always map exceptions to DebuggerErrorCode
- Provide actionable error messages
- Include context in exception messages
- Use CompletableFuture.exceptionally for async error handling

### Documentation

- Add JavaDoc to all public APIs
- Document complex logic with comments
- Use `// DESIGN DECISION:` prefix for non-obvious choices
- Add `// TODO:` for future improvements

### When Uncertain

1. **Choose the simpler option** - maintainability over cleverness
2. **Document the decision** - explain why in code comments
3. **Keep moving forward** - don't block on perfection
4. **Mark for review** - use TODO if uncertain about correctness

---

## Progress Tracking

After each phase:

### Test Execution

```bash
# Run phase-specific tests
mvn test -Dtest="**/debugger/**/*Test"

# Verify build
mvn clean package

# Run integration test
mvn exec:java
```

### Status Update

Document completion:
- [ ] All tasks in phase complete
- [ ] All tests passing
- [ ] No compilation errors
- [ ] Performance targets met (if applicable)
- [ ] Code coverage >80%
- [ ] Ready for next phase

---

## Completion Checklist

### Final Validation

Before considering implementation complete:

- [ ] All 7 phases implemented
- [ ] All 16 existing tools migrated to async
- [ ] All 10 debugger tools implemented
- [ ] All tests passing (unit + integration)
- [ ] All performance targets validated
- [ ] Works on JDK 11, 17, 21
- [ ] JDK 17+ JPMS check working
- [ ] No resource leaks
- [ ] SimpleMCPServerExample includes debugger
- [ ] README.md updated
- [ ] Documentation complete
- [ ] Security review complete
- [ ] Code coverage >80%

### Known Limitations

Document any known limitations or future improvements:
- Expression evaluator limitations (if any)
- Performance characteristics
- JDK version-specific behaviors
- Future enhancement opportunities

---

## Estimated Timeline

**Total Effort**: ~408 hours

**Breakdown**:
- Phase 0: 4 hours
- Phase 1a: 20-30 hours (tool migration)
- Phase 1b: 60 hours (foundation)
- Phase 2: 58 hours (core debugging)
- Phase 3: 46 hours (variables)
- Phase 4: 58 hours (expressions)
- Phase 5: 42 hours (advanced)
- Phase 6: 42 hours (observability)
- Phase 7: 78 hours (testing)

**Calendar Time**: 10+ weeks of focused development

---

## Success Criteria

The implementation is successful when:

1. ✅ All existing tools work with async MCPTool interface
2. ✅ All 10 debugger tools functional via MCP
3. ✅ Can debug Java applications via JDWP self-attach
4. ✅ All breakpoint types working (line, conditional, exception, method, watchpoint, logpoint)
5. ✅ All stepping operations working (in, over, out, continue, pause)
6. ✅ Variable inspection working for all types
7. ✅ Expression evaluation working (simple + complex)
8. ✅ Hot reload integration working with breakpoint migration
9. ✅ Virtual thread support working (millions of threads)
10. ✅ All performance targets met and validated
11. ✅ Works on JDK 11, 17, 21
12. ✅ Comprehensive test suite (>80% coverage)
13. ✅ Production-ready (security reviewed, no leaks)
14. ✅ Documentation complete

---

**Status**: Ready for autonomous implementation
**Next Action**: Begin Phase 0 (add dependencies, update docs, validate build)
**Expected Completion**: 10+ weeks from start

---

**End of Implementation Plan**
