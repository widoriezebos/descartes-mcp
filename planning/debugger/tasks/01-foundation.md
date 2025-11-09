# Phase 1: Foundation

**Timeline**: Week 1-2
**Status**: Not Started
**Priority**: P0 (Blocking)

---

## Overview

This phase establishes the foundational infrastructure for the Descartes debugger:
- Modern MCP plumbing so debugger tools execute asynchronously and can stream rich notifications.
- A robust JDWP connection mechanism (loopback attach) with state caching and clear documentation for required JVM flags.
- A correctly synchronized event processing pipeline based on a strict threading model.
- Core service architecture with a clean lifecycle and state management.
- Basic session management and data models.
- The definition of integration contracts for features like hot reload, to be implemented in later phases.

**Success Criteria**:
- Debug session can be started and stopped reliably.
- EventHub correctly dispatches events to a dedicated executor, preventing race conditions.
- Can list threads in the debugged application.
- All necessary JVM flags and potential security risks are documented.
- MCP tools follow the new asynchronous contract and the server can emit structured notifications.
- Unit tests cover core functionality.

### Threading Architecture (Authoritative Guidance)

1.  **EventHub Thread**: `EventHub` runs on a dedicated daemon thread (`EventHub-Thread`). Its sole responsibility is to perform a blocking read on the JDI `EventQueue`.
2.  **Synchronous Handoff**: Upon receiving an `EventSet`, the `EventHub-Thread` **must** hand it off to the `debuggerExecutor` for processing and **must wait** for the processing to complete. This ensures event handlers (e.g., for breakpoints) have fully executed before the debuggee's threads are potentially resumed.
3.  **Single Debugger Executor**: All debugger logic that interacts with the JDI (inspecting variables, setting breakpoints, stepping, evaluating expressions) **must** be executed on a single-threaded `debuggerExecutor`. This serializes all stateful operations on the target VM, preventing race conditions and ensuring a consistent view of the debuggee.
4.  **Asynchronous Offloading**: Subscribers to `EventHub`'s RxJava streams **must** use the `observeOn(Schedulers.from(debuggerExecutor))` operator. This shifts event handling from the `EventHub-Thread` to the `debuggerExecutor`, keeping the event queue responsive while respecting the single-threaded interaction model.
5.  **Non-Blocking MCP Tools**: All MCP tool implementations must return a `CompletableFuture` immediately. The body of the future's work is submitted to the `debuggerExecutor`, ensuring that the MCP server's network threads are never blocked by debugger operations.

This model is critical for stability and must be adhered to throughout all subsequent implementation phases.

---

## Task 1.0: MCP Async Infrastructure

**Estimated Time**: 10 hours  
**Assignee**: TBD  
**Dependencies**: None

### Description

Refactor the MCP server/tool layer so every tool executes asynchronously and debugger components can publish JSON-RPC notifications via a dedicated dispatcher.

### Subtasks

1. **Update `MCPTool` contract**:
   ```java
   public interface MCPTool extends AutoCloseable {
       String getToolName();
       String getToolDescription();
       Map<String, Object> getToolSchema();
       CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments);
   }
   ```
   - Create `ToolResponse` to model the standard MCP response payload (content sections, optional metadata, helpers like `ToolResponse.text(String)`).
   - Introduce `ToolExecutionException` to encapsulate rich error data in failed futures.

2. **Refactor existing tools** (`tools/*`, profiler, JShell, etc.) to implement `executeAsync` and return completed futures.

3. **Extend `MCPServer`**:
   - Route `tools/call` requests through the new async signature.
   - Write responses using a dedicated single-threaded writer executor to maintain order while keeping the listener thread non-blocking.
   - Surface tool execution errors by completing the future exceptionally and mapping failures to JSON-RPC error envelopes.
   - Expose a `ConnectionLifecycleListener` so components can react to socket open/close events (e.g., stash/remove the dispatcher in the shared context).

4. **Introduce `MCPNotificationDispatcher`**:
   ```java
   public final class MCPNotificationDispatcher implements Closeable {
       public void sendNotification(String method, Map<String, Object> params);
       public void sendMessage(String text); // convenience for notifications/message
   }
   ```
   - Own the socket writer for each connection.
   - Serialize JSON-RPC 2.0 notifications (`{"jsonrpc":"2.0","method":..., "params":...}`) via an internal bounded queue and executor.
   - Expose the dispatcher to tools via the shared context map as `context.put("mcp.dispatcher", dispatcher)`.

5. **Add unit/integration tests** covering async tool execution, error propagation, and notification delivery sequencing.

### Acceptance Criteria

- [ ] `MCPTool` refactor compiles and all existing tools return `CompletableFuture`.
- [ ] `ToolResponse`/`ToolExecutionException` established and adopted.
- [ ] `MCPServer` processes tool invocations asynchronously without blocking the listener thread.
- [ ] `MCPNotificationDispatcher` can emit notifications while requests are in flight; dispatcher lifecycle is tested per connection.
- [ ] Context map exposes the dispatcher for downstream components (debugger).

### Notes

- Notifications should use namespaced methods such as `descartes/debugger.stopped` and remain compliant with the MCP JSON-RPC framing.
- **BREAKING CHANGE**: This refactor changes the `MCPTool` interface signature. All 16 existing tools must be migrated to return `CompletableFuture<ToolResponse>` instead of `String`.
- **Migration Strategy for Existing Tools**:
  ```java
  // Pattern for migrating synchronous tools to async:
  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        // Existing synchronous logic here
        String result = performOperation(arguments);
        return ToolResponse.success(result);
      } catch (DebuggerException e) {
        return ToolResponse.error(e.getErrorCode(), e.getMessage());
      } catch (Exception e) {
        return ToolResponse.error(categorizeException(e), e.getMessage());
      }
    }, executorService); // Use appropriate executor (debugger or default)
  }
  ```
- **Tools to Migrate** (16 total):
  - Core tools: JShellTool, JShellSessionTool, ObjectInspectorTool
  - Hot reload: HotClassReloadTool
  - Monitoring: ProcessInspectorTool, SystemMonitoringTool, ThreadAnalyzerTool, MemoryAnalyzerTool
  - Analysis: ExceptionAnalysisTool, LoggingIntegrationTool
  - Profiler (6 tools): ProfilerStartTool, ProfilerStopTool, ProfilerHotspotsTool, ProfilerCallTreeTool, ProfilerListTool, ProfilerExportTool
- The debugger requires **JDK 11+ minimum** (not JDK 21+); document this in README (Phase 0).

---
---

## Task 1.1: Project Structure and Dependencies

**Estimated Time**: 4 hours
**Assignee**: TBD
**Dependencies**: None

### Description

Create the package structure and add required dependencies for the debugger subsystem.

### Subtasks

1.  **Create package structure**:
    ```
    src/main/java/com/bitsapplied/descartes/debugger/
    ├── DebuggerService.java
    ├── DebugSession.java
    ├── DebugSettings.java
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
    │   └── DebugSessionConfig.java
    ├── exceptions/
    │   ├── DebuggerException.java
    │   └── DebuggerErrorCode.java
    └── tools/
        ├── DebuggerSessionTool.java
        ├── DebuggerBreakpointsTool.java
        └── DebuggerStepTool.java
    ```

2.  **Add Maven dependencies** to `pom.xml`:
    ```xml
    <!-- RxJava for reactive event processing -->
    <dependency>
        <groupId>io.reactivex.rxjava3</groupId>
        <artifactId>rxjava</artifactId>
        <version>3.1.8</version>
    </dependency>

    <!-- Existing dependencies already available -->
    <!-- - com.google.code.gson:gson -->
    <!-- - java.util.concurrent.CompletableFuture (Java built-in) -->
    ```

    > **Note**: JDI and Attach APIs ship with the JDK as the `jdk.jdi` and `jdk.attach` modules. Ensure the build uses JDK 21 (the project default) and, when launching, add `--add-modules jdk.attach,jdk.jdi` if they are not resolved automatically.

3.  **Create test directory structure**:
    ```
    src/test/java/com/bitsapplied/descartes/debugger/
    ├── DebuggerServiceTest.java
    ├── JDWPConnectorTest.java
    ├── EventHubTest.java
    ├── BreakpointManagerTest.java
    ├── integration/
    │   ├── FullDebuggingSessionTest.java
    │   └── MultiThreadedDebuggingTest.java
    └── testutils/
        ├── DebuggerTestBase.java
        └── SimpleTestApplication.java
    ```

### Acceptance Criteria

- [ ] All packages created
- [ ] Dependencies added to pom.xml
- [ ] Project compiles successfully
- [ ] Test structure in place
- [ ] No compilation errors

### Notes

- Use RxJava 3.x (latest stable)
- **IMPORTANT**: Document clearly in the project's main `README.md` that running or testing the debugger requires launching the JVM with `-Djdk.attach.allowAttachSelf=true`.
- JDI access now relies on the `jdk.jdi` / `jdk.attach` modules (no legacy `tools.jar`).
- Opening a JDWP listener exposes a debugging port—limit usage to trusted networks and document the risk for operators.

---

## Task 1.2: Core Data Models

**Estimated Time**: 6 hours
**Assignee**: TBD
**Dependencies**: Task 1.1

### Description

Define core data models and interfaces for debugger operations.

### Subtasks

1.  **Create DebugSessionConfig.java**:
    ```java
    public record DebugSessionConfig(
        boolean suspendOnStart,
        boolean enableHotReload,
        int jdwpTimeout,
        boolean asyncJDWP,
        StepFilters stepFilters
    ) {
        public static DebugSessionConfig defaultConfig() {
            return new DebugSessionConfig(
                false,  // Don't suspend on start
                true,   // Enable hot reload integration
                3000,   // 3 second JDWP timeout
                false,  // Auto-detect async JDWP
                StepFilters.defaultFilters()
            );
        }
    }
    ```

2.  **Create ThreadInfo.java**:
    ```java
    public class ThreadInfo {
        private final long id;
        private final String name;
        private final ThreadState state;
        private final String suspendedReason;  // "breakpoint", "step", "pause", null
        private final Location suspendedLocation;
        private final boolean isVirtual;

        // Constructor, getters, builder
    }

    public enum ThreadState {
        RUNNABLE,
        BLOCKED,
        WAITING,
        TIMED_WAITING,
        SUSPENDED,
        TERMINATED
    }
    ```

3.  **Create StackFrameInfo.java**:
    ```java
    public class StackFrameInfo {
        private final int id;  // Unique ID for this frame
        private final String methodName;
        private final String className;
        private final String sourceFile;
        private final int lineNumber;
        private final int columnNumber;  // Usually 0 for Java

        // Constructor, getters, builder
    }
    ```

4.  **Create VariableInfo.java**:
    ```java
    public class VariableInfo {
        private final String name;
        private final String value;  // String representation
        private final String type;
        private final int variablesReference;  // 0 = primitive, >0 = complex object

        // Constructor, getters, builder
    }

    public enum VariableScope {
        LOCAL,    // Local variables and parameters
        THIS,     // This object fields
        STATIC,   // Static fields
        ALL       // All of the above
    }
    ```

5.  **Create BreakpointSpec.java**:
    ```java
    public class BreakpointSpec {
        private int line;
        private String condition;          // Optional: "x > 10"
        private Integer hitCondition;      // Optional: break on Nth hit
        private String logMessage;         // Optional: "User {user.name} logged in"

        // Constructor, getters, setters, builder
    }
    ```

6.  **Create DebuggerErrorCode.java**:
    ```java
    public enum DebuggerErrorCode {
        // Session errors (1000-1099)
        SESSION_NOT_ACTIVE(1000, "No active debug session"),
        SESSION_ALREADY_ACTIVE(1001, "Debug session already active"),
        SESSION_START_FAILED(1002, "Failed to start debug session"),
        JDWP_CONNECTION_FAILED(1003, "Failed to connect to JDWP"),

        // Breakpoint errors (1100-1199)
        BREAKPOINT_SET_FAILED(1100, "Failed to set breakpoint"),
        BREAKPOINT_INVALID_LOCATION(1101, "Invalid breakpoint location"),
        BREAKPOINT_CONDITION_INVALID(1102, "Breakpoint condition is invalid"),
        BREAKPOINT_NOT_FOUND(1103, "Breakpoint not found"),

        // Thread errors (1200-1299)
        THREAD_NOT_FOUND(1200, "Thread not found"),
        THREAD_NOT_SUSPENDED(1201, "Thread is not suspended"),
        STEP_FAILED(1202, "Step operation failed"),

        // General errors (1900-1999)
        UNKNOWN_ERROR(1900, "Unknown error occurred");

        private final int code;
        private final String message;

        DebuggerErrorCode(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() { return code; }
        public String getMessage() { return message; }
    }
    ```

7.  **Create DebuggerException.java**:
    ```java
    public class DebuggerException extends RuntimeException {
        private final DebuggerErrorCode errorCode;
        private final String details;

        public DebuggerException(DebuggerErrorCode errorCode, String details) {
            super(errorCode.getMessage() + ": " + details);
            this.errorCode = errorCode;
            this.details = details;
        }

        public DebuggerException(DebuggerErrorCode errorCode, String details, Throwable cause) {
            super(errorCode.getMessage() + ": " + details, cause);
            this.errorCode = errorCode;
            this.details = details;
        }

        public DebuggerErrorCode getErrorCode() { return errorCode; }
        public String getDetails() { return details; }
    }
    ```

### Acceptance Criteria

- [ ] All model classes created with proper JavaDoc
- [ ] Classes use appropriate Java features (records, enums, builders)
- [ ] Immutable where possible
- [ ] toString() methods for debugging
- [ ] Unit tests for builders and validation

### Notes

- Consider using records for immutable data classes (Java 16+)
- Provide builder pattern for complex objects
- Follow existing Descartes patterns

---

## Task 1.3: JDWP Connection Infrastructure

**Estimated Time**: 12 hours
**Assignee**: TBD
**Dependencies**: Task 1.2

### Description

Implement a robust JDWP loopback connection mechanism to enable self-debugging, including state caching and flexible address parsing.

### Subtasks

1.  **Create JDWPConnector.java**:
    ```java
    public class JDWPConnector {
        private static final Logger logger = Logger.getLogger(JDWPConnector.class.getName());
        private static final AtomicInteger attachedPort = new AtomicInteger(-1);

        /**
         * Attach to current JVM via JDWP loopback connection.
         *
         * @param timeout Connection timeout in milliseconds
         * @return VirtualMachine instance
         * @throws DebuggerException if connection fails
         */
        public static VirtualMachine attachToSelf(int timeout) throws DebuggerException {
            try {
                // Caching: If already attached, reuse the connection info.
                if (attachedPort.get() != -1) {
                    logger.info("Reusing existing JDWP attachment on port " + attachedPort.get());
                    return attachToLocalhost(attachedPort.get(), timeout);
                }

                // 1. Check if JDWP already enabled via startup flags
                int jdwpPort = getExistingJDWPPort();

                if (jdwpPort == -1) {
                    // 2. Dynamically enable JDWP if not already running
                    requireSelfAttachEnabled();
                    jdwpPort = enableJDWP();
                }

                // 3. Cache the port and attach via loopback
                attachedPort.set(jdwpPort);
                return attachToLocalhost(jdwpPort, timeout);

            } catch (Exception e) {
                throw new DebuggerException(
                    DebuggerErrorCode.JDWP_CONNECTION_FAILED,
                    "Failed to attach to JVM via JDWP",
                    e
                );
            }
        }

        private static int getExistingJDWPPort() {
            // Parse -agentlib:jdwp=... from RuntimeMXBean
            String jdwpArg = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.contains("agentlib:jdwp"))
                .findFirst().orElse(null);

            if (jdwpArg == null) return -1;

            // Broaden regex to find port in "address=8000", "address=localhost:8000", etc.
            Pattern pattern = Pattern.compile("address=(?:[\\w.-]+:)?(\\d+)");
            Matcher matcher = pattern.matcher(jdwpArg);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }

            return -1;  // JDWP enabled but address not parsable
        }

        private static void requireSelfAttachEnabled() {
            String allowAttach = System.getProperty("jdk.attach.allowAttachSelf");
            if (!Boolean.parseBoolean(allowAttach)) {
                throw new DebuggerException(
                    DebuggerErrorCode.JDWP_CONNECTION_FAILED,
                    "Self-attach is disabled. Launch JVM with -Djdk.attach.allowAttachSelf=true " +
                    "and add the jdk.attach module."
                );
            }
        }

        private static int enableJDWP() throws Exception {
            // Use VirtualMachine.attach() to dynamically enable JDWP
            String pid = Long.toString(ProcessHandle.current().pid());
            com.sun.tools.attach.VirtualMachine vm = com.sun.tools.attach.VirtualMachine.attach(pid);

            try {
                // agent arguments: transport, server mode, address (0 = dynamic port), suspend
                String agentArgs = "transport=dt_socket,server=y,address=127.0.0.1:0,suspend=n";
                vm.loadAgentLibrary("jdwp", agentArgs);

                // Read the actual port from agent properties
                Properties agentProps = vm.getAgentProperties();
                String address = agentProps.getProperty("sun.jdwp.listenerAddress");
                if (address == null || !address.contains(":")) {
                    throw new IllegalStateException("JDWP agent did not publish listener address");
                }

                // Parse host:port (expected 127.0.0.1:PORT or [::1]:PORT)
                String[] hostPort = address.split(":");
                String host = hostPort[0];
                int port = Integer.parseInt(hostPort[hostPort.length - 1]);
                if (!host.equals("127.0.0.1") && !host.equals("::1")) {
                    logger.warning("JDWP listener bound to unexpected host " + host + "; forcing loopback attach");
                }

                logger.info("Dynamically enabled JDWP on port " + port);
                return port;
            } finally {
                vm.detach();
            }
        }

        private static VirtualMachine attachToLocalhost(int port, int timeout) throws Exception {
            VirtualMachineManager vmManager = Bootstrap.virtualMachineManager();
            AttachingConnector connector = vmManager.attachingConnectors().stream()
                .filter(c -> c.transport().name().equals("dt_socket"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("SocketAttachingConnector not available"));

            Map<String, Connector.Argument> args = connector.defaultArguments();
            args.get("hostname").setValue("127.0.0.1");
            args.get("port").setValue(String.valueOf(port));
            args.get("timeout").setValue(String.valueOf(timeout));

            logger.info("Attaching to JDWP at 127.0.0.1:" + port);
            VirtualMachine vm = connector.attach(args);
            logger.info("Successfully attached to JVM");
            return vm;
        }
    }
    ```

2.  **Create DebugSession.java**:
    ```java
    public class DebugSession {
        private final VirtualMachine vm;
        private final String sessionId;
        private final long startTime;
        private volatile boolean closed;

        public DebugSession(VirtualMachine vm) {
            this.vm = vm;
            this.sessionId = "debug-session-" + System.currentTimeMillis();
            this.startTime = System.currentTimeMillis();
            this.closed = false;
        }

        public VirtualMachine getVM() {
            checkActive();
            return vm;
        }

        public String getSessionId() {
            return sessionId;
        }

        public boolean isActive() {
            return !closed && !vm.isDisconnected();
        }

        public void close() {
            if (!closed) {
                try {
                    vm.dispose(); // Terminates JDWP connection but keeps the JVM running
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error disposing JDWP connection", e);
                } finally {
                    closed = true;
                }
            }
        }

        private void checkActive() {
            if (closed) {
                throw new DebuggerException(
                    DebuggerErrorCode.SESSION_NOT_ACTIVE,
                    "Debug session has been closed"
                );
            }
            if (vm.isDisconnected()) {
                throw new DebuggerException(
                    DebuggerErrorCode.SESSION_NOT_ACTIVE,
                    "JVM has disconnected"
                );
            }
        }

        // Convenience methods
        public List<ThreadReference> allThreads() {
            checkActive();
            return vm.allThreads();
        }

        public List<ReferenceType> classesByName(String className) {
            checkActive();
            return vm.classesByName(className);
        }

        public EventRequestManager eventRequestManager() {
            checkActive();
            return vm.eventRequestManager();
        }
    }
    ```

3.  **Add unit tests**:
    - Test successful attachment.
    - Test parsing of various `address=` formats.
    - Test that the port is cached and reused on subsequent calls.
    - Test failure when `-Djdk.attach.allowAttachSelf=true` is missing.

### Acceptance Criteria

- [ ] JDWPConnector successfully attaches to current JVM.
- [ ] Can detect existing JDWP or enable dynamically.
- [ ] JDWP port is cached after first successful dynamic attach.
- [ ] Address parsing is robust.
- [ ] Connection timeout works correctly.
- [ ] Error handling covers all failure cases.
- [ ] Unit tests pass.

### Notes

- JDWP port caching is a static field, assuming one debugger per JVM process.
- Dynamic JDWP enablement forces the listener to bind to loopback (`127.0.0.1`) to avoid exposing the debugger to untrusted networks; document this requirement for operators.

---

## Task 1.4: Event Hub with RxJava

**Estimated Time**: 10 hours
**Assignee**: TBD
**Dependencies**: Task 1.3

### Description

Implement reactive event processing using RxJava, ensuring correct synchronization with the debugger executor.

### Subtasks

1.  **Create DebugEvent.java**:
    ```java
    public class DebugEvent {
        private final Event event;
        private final EventSet eventSet;
        // This flag is now managed by the EventHub's synchronous processing logic
        // private boolean shouldResume = true;

        public DebugEvent(Event event, EventSet eventSet) {
            this.event = event;
            this.eventSet = eventSet;
        }

        public Event getEvent() { return event; }
        public EventSet getEventSet() { return eventSet; }

        public ThreadReference getThread() {
            if (event instanceof LocatableEvent) {
                return ((LocatableEvent) event).thread();
            } else if (event instanceof ThreadStartEvent) {
                return ((ThreadStartEvent) event).thread();
            } else if (event instanceof ThreadDeathEvent) {
                return ((ThreadDeathEvent) event).thread();
            }
            return null;
        }
    }
    ```

2.  **Create EventHub.java**:
    ```java
    public class EventHub {
        private static final Logger logger = Logger.getLogger(EventHub.class.getName());

        private final Subject<DebugEvent> eventSubject;
        private final ExecutorService debuggerExecutor;
        private Thread eventThread;
        private volatile boolean running;

        public EventHub(ExecutorService debuggerExecutor) {
            this.debuggerExecutor = debuggerExecutor;
            this.eventSubject = PublishSubject.<DebugEvent>create().toSerialized();
            this.running = false;
        }

        public void start(VirtualMachine vm) {
            if (running) throw new IllegalStateException("EventHub already started");
            running = true;
            eventThread = new Thread(() -> processEvents(vm), "EventHub-Thread");
            eventThread.setDaemon(true);
            eventThread.start();
            logger.info("EventHub started");
        }

        public void stop() {
            if (running) {
                running = false;
                if (eventThread != null) {
                    eventThread.interrupt();
                }
                eventSubject.onComplete();
                logger.info("EventHub stopped");
            }
        }

        public Observable<DebugEvent> events() {
            return eventSubject;
        }

        // Convenience filters...

        private void processEvents(VirtualMachine vm) {
            EventQueue eventQueue = vm.eventQueue();
            while (running && !vm.isDisconnected()) {
                try {
                    EventSet eventSet = eventQueue.remove(); // Blocking call

                    // Synchronous handoff to the debugger executor
                    CompletableFuture<Void> processingFuture = CompletableFuture.runAsync(() -> {
                        for (Event event : eventSet) {
                            logger.fine("JDI Event: " + event.getClass().getSimpleName());
                            eventSubject.onNext(new DebugEvent(event, eventSet));
                        }
                    }, debuggerExecutor);

                    // Wait for all handlers on the executor to finish
                    processingFuture.join();

                    // Now, decide whether to resume. The default is to resume unless
                    // the suspend policy of an event dictates otherwise.
                    if (eventSet.suspendPolicy() != EventRequest.SUSPEND_ALL) {
                         eventSet.resume();
                    }
                } catch (InterruptedException e) {
                    logger.info("EventHub interrupted");
                    break;
                } catch (VMDisconnectedException e) {
                    logger.info("VM disconnected");
                    eventSubject.onError(e);
                    break;
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error processing events", e);
                    eventSubject.onError(e);
                    break;
                }
            }
            running = false;
        }
    }
    ```

3.  **Add unit tests**:
    - Test that events are correctly passed to subscribers on the `debuggerExecutor`.
    - Test that the `EventHub-Thread` blocks until subscribers have finished.
    - Test that `stop()` correctly terminates the thread and completes the subject.

### Acceptance Criteria

- [ ] EventHub starts and processes JDI events.
- [ ] Event processing is handed off to the `debuggerExecutor` synchronously.
- [ ] The `EventHub-Thread` waits for processing to complete before resuming the event set.
- [ ] `stop()` provides a clean shutdown path.
- [ ] Thread-safe event processing is guaranteed by the model.
- [ ] Graceful shutdown on VM disconnect.
- [ ] Unit tests pass.

### Notes

- The `shouldResume` flag is removed from `DebugEvent` as the decision is now centralized based on the `EventSet`'s suspend policy after all handlers have run. Individual handlers can vote to suspend the VM if needed, which would affect subsequent event processing.

---

## Task 1.5: DebuggerService Core

**Estimated Time**: 8 hours
**Assignee**: TBD
**Dependencies**: Task 1.4

### Description

Implement the core DebuggerService with a clean session lifecycle, state management, and basic operations.

### Subtasks

1.  **Create DebuggerService.java**:
    ```java
    public class DebuggerService {
        private static final Logger logger = Logger.getLogger(DebuggerService.class.getName());

        private final Map<String, Object> context;
        private DebugSession currentSession;
        private EventHub eventHub;
        private BreakpointManager breakpointManager;
        private ThreadStateManager threadStateManager;
        private MCPEventBridge mcpEventBridge;
        private MCPNotificationDispatcher notificationDispatcher;
        private final ExecutorService debuggerExecutor;

        public DebuggerService(Map<String, Object> context) {
            this.context = context;
            this.debuggerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "descartes-debugger"));
        }

        public CompletableFuture<DebugSession> startSession(DebugSessionConfig config) {
            return CompletableFuture.supplyAsync(() -> {
                if (isActive()) {
                    throw new DebuggerException(DebuggerErrorCode.SESSION_ALREADY_ACTIVE, "...");
                }
                try {
                    VirtualMachine vm = JDWPConnector.attachToSelf(config.jdwpTimeout());
                    currentSession = new DebugSession(vm);
                    notificationDispatcher = (MCPNotificationDispatcher) context.get("mcp.dispatcher");
                    if (notificationDispatcher == null) {
                        throw new DebuggerException(DebuggerErrorCode.CONFIGURATION_INVALID,
                            "MCPNotificationDispatcher missing from context");
                    }

                    eventHub = new EventHub(debuggerExecutor);
                    eventHub.start(vm);
                    breakpointManager = new BreakpointManager(vm, eventHub, debuggerExecutor);
                    threadStateManager = new ThreadStateManager(vm, eventHub, debuggerExecutor);
                    mcpEventBridge = new MCPEventBridge(notificationDispatcher, eventHub, this, debuggerExecutor);
                    mcpEventBridge.start();

                    if (config.suspendOnStart()) vm.suspend();
                    
                    context.put("debugSession", currentSession);
                    logger.info("Debug session started: " + currentSession.getSessionId());
                    return currentSession;
                } catch (Exception e) {
                    throw new DebuggerException(DebuggerErrorCode.SESSION_START_FAILED, "...", e);
                }
            }, debuggerExecutor);
        }

        public CompletableFuture<Void> stopSession() {
            return CompletableFuture.runAsync(() -> {
                if (!isActive()) {
                    throw new DebuggerException(DebuggerErrorCode.SESSION_NOT_ACTIVE, "...");
                }
                try {
                    if (mcpEventBridge != null) mcpEventBridge.stop();
                    if (eventHub != null) eventHub.stop();
                    if (currentSession != null) currentSession.close();
                    
                    context.remove("debugSession");
                    logger.info("Debug session stopped");
                } catch (Exception e) {
                    throw new DebuggerException(DebuggerErrorCode.UNKNOWN_ERROR, "...", e);
                } finally {
                    // Clear all state
                    mcpEventBridge = null;
                    eventHub = null;
                    currentSession = null;
                    breakpointManager = null;
                    threadStateManager = null;
                    notificationDispatcher = null;
                }
            }, debuggerExecutor);
        }

        public boolean isActive() {
            return currentSession != null && currentSession.isActive();
        }

        public boolean isRunning() {
            // Implementation depends on tracking suspend/resume state.
            // For now, can infer from thread states.
            return isActive() && getCurrentSession().getVM().allThreads().stream()
                .noneMatch(ThreadReference::isSuspended);
        }
        
        // ... other methods (listThreads, etc.)
    }
    ```

2.  **Create ThreadStateManager.java** (skeleton):
    - Must clear all per-thread state upon receiving a `ThreadDeathEvent`.

3.  **Create BreakpointManager.java** (skeleton):
    - Must clear any per-thread cached data (e.g., compiled expressions) upon receiving a `ThreadDeathEvent`.

4.  **Add integration test**:
    - Test `startSession` followed immediately by `stopSession` to ensure clean resource disposal.
    - Test `isRunning()` in both suspended and running states.

### Acceptance Criteria

- [ ] DebuggerService manages session lifecycle cleanly.
- [ ] `stopSession` correctly disposes of all resources (event hub thread, subscriptions).
- [ ] `isRunning` provides a reasonable view of the debuggee state.
- [ ] Error handling for all operations is robust.
- [ ] Integration tests pass.

---

## Task 1.6: Basic MCP Tool - debugger_session

**Estimated Time**: 4 hours
**Assignee**: TBD
**Dependencies**: Task 1.5

### Description

Implement the first MCP tool for session management.

### Subtasks

1.  **Create DebuggerSessionTool.java**:
    - Implement `start`, `stop`, and `status` operations via `CompletableFuture<ToolResponse> executeAsync(...)`.
    - Ensure all logic is dispatched to the `debuggerExecutor`.
    - Provide clear success and error responses (use `ToolResponse.text(...)` for simple messages).

2.  **Add to SimpleMCPServerExample**:
    - Register the tool with the server instance.

3.  **Add integration test**:
    - Test the full tool lifecycle by invoking `executeAsync()` and awaiting completion in tests.

### Acceptance Criteria

- [ ] Tool implements MCPTool interface correctly.
- [ ] All operations work as expected.
- [ ] Asynchronous execution model is respected.
- [ ] Integrated with the example server.
- [ ] Integration test passes.

---

## Task 1.7: Define Hot Reload Integration Contract

**Estimated Time**: 2 hours
**Assignee**: TBD
**Dependencies**: Task 1.1

### Description

Define and document the dedicated event bus that `HotReloadService` exposes so other components (the debugger) can subscribe safely.

### Subtasks

1.  **Design `HotReloadEventBus`**:
    ```java
    public final class HotReloadEventBus implements Closeable {
        private final ExecutorService executor; // single-threaded ordering

        public <T extends HotReloadEvent> AutoCloseable subscribe(Class<T> type, Consumer<T> listener);
        public void publish(HotReloadEvent event);
        public void close();
    }
    ```
    - Events execute on a dedicated single-thread executor owned by the bus to preserve ordering and isolate listener failures.
    - Listeners receive immutable event payloads and may return `CompletableFuture<Void>` for async work in later phases.

2.  **Define `HotReloadCompletionEvent`**:
    ```java
    public record HotReloadCompletionEvent(Instant completedAt,
                                           List<String> reloadedClassNames,
                                           boolean forced) implements HotReloadEvent {}
    ```
    - Capture timing metadata so the debugger can correlate with breakpoints/profiling.

3.  **Integrate with `HotReloadService`**:
    - Add an instance of `HotReloadEventBus` to the service, publish the completion event after redefinition succeeds, and publish failure events later if needed.
    - Document lifecycle (bus created in constructor, closed during service shutdown).

4.  **Document subscription pattern** (update `planning/debugger/tasks/05-advanced.md`):
    ```java
    hotReloadService.getEventBus()
        .subscribe(HotReloadCompletionEvent.class, event -> CompletableFuture.runAsync(
            () -> breakpointMigration.schedule(event.reloadedClassNames()),
            debuggerExecutor
        ));
    ```

### Acceptance Criteria

- [ ] `HotReloadEventBus` contract defined with ordering/executor guarantees.
- [ ] `HotReloadCompletionEvent` payload shape approved.
- [ ] Integration notes added to HotReloadService documentation and Phase 5 plan reflects the new API.

### Notes

- The bus will be implemented in Phase 5 alongside the hot reload integration, but its contract is now stable for downstream planning.

---

## Phase 1 Completion Checklist

- [ ] All tasks completed
- [ ] Unit tests passing (>80% coverage)
- [ ] Integration tests passing
- [ ] MCP asynchronous tool + notification infrastructure validated
- [ ] Code review completed
- [ ] Documentation updated (especially JVM flags)
- [ ] Performance benchmarks run (connection <100ms, thread listing <20ms)
- [ ] Ready for Phase 2

---

**End of Phase 1 Task Document**

---

## CORRECTIONS AND ADDITIONS (Applied During Planning Review)

### Task 1.2: ThreadInfo Model - ✅ ALREADY CORRECT
The ThreadInfo model at line 232 already includes `private final boolean isVirtual;` field. No changes needed.

### Task 1.3: JDWPConnector - ADD JDK Version Check
Add the following at the start of `attachToSelf()` method:

```java
public static VirtualMachine attachToSelf(int timeout) throws DebuggerException {
    // JDK 11+ version check
    if (Runtime.version().feature() < 11) {
        throw new DebuggerException(
            DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "Debugger requires JDK 11 or later (current: " + Runtime.version() + ")"
        );
    }
    
    // ... rest of existing implementation
}
```

**Rationale**: Self-attach API is unreliable on JDK 8-10. JDK 11+ provides stable self-attach support.

### Task 1.3: JDWPConnector - ADD Circuit Breaker Pattern
Add circuit breaker to prevent infinite retry loops on persistent JDWP failures:

```java
public class JDWPConnector {
    private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final int MAX_FAILURES = 3;
    private static volatile Instant circuitOpenUntil = null;
    
    public static VirtualMachine attachToSelf(int timeout) throws DebuggerException {
        // Check circuit breaker
        if (circuitOpenUntil != null && Instant.now().isBefore(circuitOpenUntil)) {
            Duration remaining = Duration.between(Instant.now(), circuitOpenUntil);
            throw new DebuggerException(
                DebuggerErrorCode.JDWP_CONNECTION_FAILED,
                "JDWP circuit breaker open. Retry in " + remaining.toSeconds() + " seconds"
            );
        }
        
        try {
            VirtualMachine vm = /* existing attach logic */;
            // Success - reset circuit breaker
            consecutiveFailures.set(0);
            circuitOpenUntil = null;
            return vm;
        } catch (Exception e) {
            // Record failure
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= MAX_FAILURES) {
                // Open circuit for 5 minutes
                circuitOpenUntil = Instant.now().plus(Duration.ofMinutes(5));
                logger.warning("JDWP circuit breaker opened after " + failures + " failures");
            }
            throw new DebuggerException(/* ... */);
        }
    }
}
```

**Rationale**: Prevents resource exhaustion from repeated failed connection attempts. Gives system time to recover.

### Task 1.4: EventHub - RxJava Filter Pattern
When subscribing to specific event types, use `.ofType()` instead of `.filter() instanceof`:

```java
// CORRECT - use ofType()
eventHub.events()
    .ofType(BreakpointEvent.class)
    .observeOn(Schedulers.from(debuggerExecutor))
    .subscribe(event -> handleBreakpoint(event));

// AVOID - manual instanceof filtering
eventHub.events()
    .filter(e -> e.getEvent() instanceof BreakpointEvent)
    .observeOn(Schedulers.from(debuggerExecutor))
    .subscribe(event -> handleBreakpoint((BreakpointEvent) event.getEvent()));
```

**Rationale**: `.ofType()` is more idiomatic RxJava and provides automatic type casting.

### Task 1.5: DebuggerService - ADD SessionState State Machine
Add explicit state machine for session lifecycle tracking:

```java
public enum SessionState {
    CREATED,        // Session instantiated but not connected
    CONNECTING,     // JDWP connection in progress
    READY,          // Connected and idle
    SUSPENDED,      // Hit breakpoint or paused
    STEPPING,       // Step operation in progress
    EVALUATING,     // Expression evaluation in progress
    DISCONNECTING,  // Shutdown initiated
    CLOSED;         // Session terminated
    
    private static final Map<SessionState, Set<SessionState>> VALID_TRANSITIONS = Map.of(
        CREATED, Set.of(CONNECTING, CLOSED),
        CONNECTING, Set.of(READY, CLOSED),
        READY, Set.of(SUSPENDED, STEPPING, EVALUATING, DISCONNECTING),
        SUSPENDED, Set.of(READY, STEPPING, EVALUATING, DISCONNECTING),
        STEPPING, Set.of(READY, SUSPENDED, DISCONNECTING),
        EVALUATING, Set.of(READY, SUSPENDED, DISCONNECTING),
        DISCONNECTING, Set.of(CLOSED),
        CLOSED, Set.of()
    );
    
    public boolean canTransitionTo(SessionState target) {
        return VALID_TRANSITIONS.get(this).contains(target);
    }
    
    public void validateTransition(SessionState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                "Invalid state transition from " + this + " to " + target
            );
        }
    }
}

// In DebuggerService:
private volatile SessionState state = SessionState.CREATED;

private synchronized void transitionTo(SessionState newState) {
    state.validateTransition(newState);
    SessionState oldState = state;
    state = newState;
    logger.info("Session state: " + oldState + " -> " + newState);
}
```

**Rationale**: Explicit state machine prevents invalid operations (e.g., evaluating when session is closed) and provides clear error messages.

### Summary of Corrections Applied:

1. ✅ **Task 1.0**: Added full MCPTool migration strategy with all 16 tools listed
2. ✅ **Task 1.0**: Changed JDK requirement from 21+ to 11+ minimum
3. ✅ **Task 1.2**: Confirmed ThreadInfo already includes `isVirtual` field
4. ⚠️  **Task 1.3**: JDK 11+ version check should be added to implementation
5. ⚠️  **Task 1.3**: Circuit breaker pattern should be added to implementation
6. ⚠️  **Task 1.4**: RxJava `.ofType()` pattern documented for future reference
7. ⚠️  **Task 1.5**: SessionState state machine should be added to implementation

**Implementation Note**: Items marked ⚠️ are documented here for implementation but not added to the code snippets above to avoid making the task document overly prescriptive. Implement these during Phase 1 execution.

---

**End of Phase 1 Task Document with Corrections**

---

### CRITICAL ADDITION: JDK 17+ JPMS Compatibility (Correction #23)

**Date Added**: 2025-11-03 (Post-Review)
**Severity**: HIGH 🔴
**Affects**: JDK 17, 18, 19, 20, 21, 22, 23+

#### The Problem

JDK 17 introduced **stronger module encapsulation** as part of JPMS (Java Platform Module System). The Attach API (`jdk.attach` module) uses reflection internally to access package-private classes in `sun.tools.attach`. Without explicit permission via `--add-opens`, this results in:

```
java.lang.reflect.InaccessibleObjectException: Unable to make field ... accessible: 
module jdk.attach does not "opens sun.tools.attach" to unnamed module
```

This will cause **immediate failure** when attempting JDWP self-attach on JDK 17+.

#### Required Flags for JDK 17+

In addition to the existing flags, JDK 17+ requires:

```bash
--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED
```

**Complete flag set for JDK 17+**:
```bash
-Djdk.attach.allowAttachSelf=true
--add-modules jdk.attach,jdk.jdi
--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED
```

#### Task 1.3 Update: Add JDK 17+ Check to JDWPConnector

Add the following check to `JDWPConnector.requireSelfAttachEnabled()`:

```java
private static void requireSelfAttachEnabled() {
    // Check 1: Self-attach enabled
    String allowAttach = System.getProperty("jdk.attach.allowAttachSelf");
    if (!Boolean.parseBoolean(allowAttach)) {
        throw new DebuggerException(
            DebuggerErrorCode.JDWP_CONNECTION_FAILED,
            "Self-attach is disabled. Launch JVM with -Djdk.attach.allowAttachSelf=true"
        );
    }
    
    // Check 2: JDK 17+ JPMS --add-opens verification
    if (Runtime.version().feature() >= 17) {
        try {
            // Attempt to list VMs - this will fail if --add-opens is missing
            // The Attach API uses reflection internally that requires --add-opens
            com.sun.tools.attach.VirtualMachine.list();
            
            logger.info("JDK 17+ JPMS check passed (--add-opens present)");
        } catch (IllegalAccessError | InaccessibleObjectException e) {
            throw new DebuggerException(
                DebuggerErrorCode.JDWP_CONNECTION_FAILED,
                "JDK 17+ requires --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED. " +
                "This flag is needed for the Attach API to work via reflection. " +
                "Current JDK: " + Runtime.version() + ". " +
                "See documentation for complete flag requirements."
            );
        } catch (Exception e) {
            // Other exceptions are fine - we just want to catch reflection errors
            logger.fine("Attach API verification successful");
        }
    }
}
```

#### Task 1.1 Update: Document JPMS Requirements

Add to Task 1.1 Notes section:

```markdown
### JPMS (Java Platform Module System) Considerations

**JDK 17+ Critical Requirement**:
- The `--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED` flag is **REQUIRED** for JDK 17+
- Without this flag, the Attach API will throw `InaccessibleObjectException`
- This is due to stronger module encapsulation introduced in JDK 17
- The flag must be present at JVM startup (cannot be added dynamically)

**Testing on JDK 17+**:
- Always test JDWP connection early in Phase 1 on JDK 17+ 
- Verify error messages are clear and actionable
- Document flag requirements prominently in README and error messages
```

#### Why This Matters

**Impact Statistics**:
- **JDK 17**: Released September 2021 (LTS)
- **JDK 21**: Released September 2023 (Latest LTS)
- **Adoption**: Many organizations are migrating from JDK 11 → JDK 17/21
- **Market Share**: JDK 17+ is rapidly becoming the dominant version

**Without this fix**:
- ❌ Debugger will fail immediately on JDK 17+ with cryptic errors
- ❌ Poor first-run experience on modern JDKs
- ❌ High support burden
- ❌ Perception that debugger is broken

**With this fix**:
- ✅ Clear, actionable error message
- ✅ Documentation tells users exactly what's needed
- ✅ Works seamlessly on JDK 17+
- ✅ Future-proof for JDK 21, 22, 23+

#### Additional Flags That May Be Needed

In some environments, additional `--add-opens` flags may be required:

```bash
# May be needed for certain JDI operations
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
```

**Recommendation**: Start with just `jdk.attach/sun.tools.attach` and add others only if specific errors occur. Document any additional flags discovered during testing in Phase 7.

#### Integration with Existing Corrections

This correction **complements** existing Correction #4 (JDK 11+ version check):

**Correction #4** (Existing):
```java
if (Runtime.version().feature() < 11) {
    throw new DebuggerException(/* JDK 11+ required */);
}
```

**Correction #23** (New - JDK 17+ JPMS):
```java
if (Runtime.version().feature() >= 17) {
    // Verify --add-opens is present
    verifyJPMSFlags();
}
```

Both checks should be in `JDWPConnector.attachToSelf()` or `requireSelfAttachEnabled()`.

---

**End of JDK 17+ JPMS Compatibility Addition**
