# Phase 7: Testing & Polish

**Timeline**: Week 9-10
**Status**: Not Started
**Priority**: P0 (Blocking)
**Dependencies**: Phase 6 Complete

---

## Overview

Comprehensive testing, bug fixes, and production readiness:
- Unit test coverage >80%
- Integration tests for all features
- Performance optimization
- Bug fixes from testing
- Production readiness checklist
- Release documentation

**Success Criteria**:
- All tests passing
- Performance targets met
- No known critical bugs
- Production-ready code
- Complete documentation

---

## Task 7.1: Unit Test Coverage

**Time**: 12 hours

### Coverage Analysis
```bash
mvn test jacoco:report

# Target: >80% line coverage for debugger package
```

### Fill Coverage Gaps
```java
// Identify untested code paths
// Write tests for:
// - Edge cases
// - Error conditions
// - Boundary conditions
// - Thread safety

@Test
void testBreakpointConcurrentModification() {
    // Multiple threads setting breakpoints simultaneously
}

@Test
void testSessionStopDuringBreakpoint() {
    // Stop session while thread suspended at breakpoint
}

@Test
void testInvalidJDWPConnection() {
    // JDWP port unavailable
}

@Test
void testVMDisconnectDuringOperation() {
    // VM disconnects mid-operation
}

@Test
void testLargeStackTrace() {
    // 1000+ frame stack (recursion)
}

@Test
void testUnicodeInVariableNames() {
    // Variables with unicode characters
}

@Test
void testThreadDeathDuringStep() {
    // Thread dies while stepping
}
```

### Critical Path Tests
```java
@Test
void testFullDebuggingCycle() {
    // Start → Breakpoint → Hit → Inspect → Step → Evaluate → Continue → Stop
}

@Test
void testBreakpointLifecycle() {
    // Set → Verify → Hit → Modify → Hit again → Remove
}

@Test
void testHotReloadCycle() {
    // Debug → Hot reload → Breakpoints migrate → Continue debugging
}
```

### Tests
- Coverage report generated
- All untested paths covered
- Critical paths tested
- Coverage >80%
- All debugger operations confirmed to run via the single-threaded `debuggerExecutor`

**Acceptance**: Comprehensive unit test coverage

---

## Task 7.2: Integration Test Suite

**Time**: 16 hours

### Multi-Feature Integration Tests
```java
@Test
void testConditionalBreakpointWithStepping() {
    // Set conditional breakpoint
    // Hit when condition true
    // Step through
    // Hit again when condition true
    // Continue
}

@Test
void testWatchExpressionsWithHotReload() {
    // Add watch expression
    // Hit breakpoint → watch evaluated
    // Hot reload changes code
    // Hit breakpoint again → watch still works
}

@Test
void testExceptionBreakpointWithStack() {
    // Set exception breakpoint
    // Throw exception
    // Stop at throw point
    // Inspect stack trace
    // Inspect exception object
}

@Test
void testMultiThreadedBreakpoints() {
    // Set breakpoint in shared code
    // Start 20 threads hitting breakpoint
    // Each thread suspends independently
    // Can inspect each thread's variables
    // Resume threads one by one
}

@Test
void testComplexDebuggingSession() {
    // Multiple breakpoint types
    // Multiple threads
    // Hot reload
    // Expression evaluation
    // Watch expressions
    // Logpoints
    // All working together
}
```

### Real Application Testing
```java
// Create realistic test applications

public class OrderProcessingApp {
    // Multi-threaded order processing
    // Database interactions
    // Complex business logic
    // Exception scenarios
}

@Test
void testDebugOrderProcessing() {
    OrderProcessingApp app = new OrderProcessingApp();

    // Set breakpoints at key points
    // Process orders
    // Debug through workflow
    // Verify correct behavior
}
```

### Stress Tests
```java
@Test
void test1000Breakpoints() {
    // Set 1000 breakpoints across codebase
    // Hit various breakpoints
    // Verify performance acceptable
}

@Test
void test100ConcurrentThreads() {
    // 100 threads, multiple hitting breakpoints
    // Inspect all suspended threads
    // Resume all threads
}

@Test
void testLongRunningSession() {
    // Debug session active for 8 hours
    // Regular debug operations
    // Verify no memory leaks
    // Verify no performance degradation
}
```

### Tests
- All integration tests pass
- Real application debugging works
- Stress tests pass
- No memory leaks
- Debugger executor serialization verified

**Acceptance**: Comprehensive integration testing

---

## Task 7.3: Performance Optimization

**Time**: 10 hours

### Profiling
```java
// Profile debugger operations
// Identify bottlenecks
// Optimize hot paths

// Example findings:
// - Variable extraction: 40% time in JDWP calls
//   → Solution: Batch JDWP requests
// - Expression evaluation: 30% time in compiled pipeline bootstrap
//   → Solution: Cache compiled expressions and reuse class loaders
// - Breakpoint hit: 15% time in notification formatting
//   → Solution: Lazy formatting
```

### Optimization Opportunities

#### 1. JDWP Call Batching
```java
// Before: Sequential calls
for (Field field : fields) {
    Value value = obj.getValue(field);  // 100 JDWP calls
}

// After: Batch call
Map<Field, Value> values = obj.getValues(fields);  // 1 JDWP call
```

#### 2. Cache Warming
```java
// Pre-fetch commonly accessed data
CompletableFuture.allOf(
    CompletableFuture.runAsync(() -> thread.frameCount(), debuggerExecutor),
    CompletableFuture.runAsync(() -> thread.name(), debuggerExecutor),
    CompletableFuture.runAsync(() -> thread.status(), debuggerExecutor)
).join();
```

#### 3. Lazy Computation
```java
// Don't format values until needed
public class Variable {
    private Value value;
    private String formattedValue;  // Computed on demand

    public String getFormattedValue() {
        if (formattedValue == null) {
            formattedValue = formatter.format(value);
        }
        return formattedValue;
    }
}
```

#### 4. Connection Pooling
```java
// Warm interpreter caches per thread
// Reuse compiled expression classes per thread
// Pool JDWP requests
```

### Performance Tests
```java
@Test
void verifyPerformanceTargets() {
    // Run all performance benchmarks
    // Verify all targets met
    // Generate performance report
}
```

### Tests
- Profiling identified bottlenecks
- Optimizations implemented
- Performance benchmarks pass
- No regressions
- Confirmed optimizations respect single-threaded debugger executor

**Acceptance**: All performance targets met

---

## Task 7.4: Bug Fixes

**Time**: 16 hours

### Bug Tracking
```markdown
# Known Issues

## Critical (P0) - Must fix before release
- [ ] #1: Race condition in ThreadStateManager
- [ ] #2: Memory leak in expression cache
- [ ] #3: Deadlock in event hub shutdown

## High (P1) - Should fix before release
- [ ] #4: Breakpoint verification sometimes incorrect
- [ ] #5: Step over doesn't work in lambdas
- [ ] #6: Watch expressions fail after hot reload

## Medium (P2) - Can defer
- [ ] #7: Error messages could be more helpful
- [ ] #8: Variable formatting truncates too aggressively
```

### Bug Fix Process
```java
// For each bug:
// 1. Write failing test
// 2. Fix bug
// 3. Verify test passes
// 4. Add regression test
// 5. Document fix

@Test
void testBug1_RaceConditionInThreadStateManager() {
    // Reproduce race condition
    // Verify fixed
}
```

### Edge Cases
```java
@Test
void testEmptyStackFrame() {
    // Native method with no stack
}

@Test
void testCircularObjectReferences() {
    // Object A references B, B references A
}

@Test
void testVeryLongStrings() {
    // 1MB string in variable
}

@Test
void testClassLoaderIssues() {
    // Multiple class loaders with same class
}
```

### Tests
- All critical bugs fixed
- All high priority bugs fixed
- Regression tests added
- Edge cases handled

**Acceptance**: No known critical bugs

---

## Task 7.5: Production Readiness

**Time**: 8 hours

### Security Review
```java
// Review security implications
// - JDWP opens debugging port → document risks
// - Expression evaluation can execute arbitrary code → document
// - Variable inspection can expose sensitive data → document

// Add security warnings
logger.warning("SECURITY: JDWP debugging port is open. " +
    "Do not expose to untrusted networks.");
```

### Error Handling Review
```java
// Verify all error paths
// - No uncaught exceptions
// - All errors logged
// - User-facing errors actionable

// Add defensive checks
if (thread == null) {
    throw new DebuggerException(
        DebuggerErrorCode.THREAD_NOT_FOUND,
        "Thread ID " + threadId + " not found. " +
        "Thread may have exited or ID is invalid."
    );
}
```

### Resource Cleanup
```java
// Verify proper cleanup
// - Breakpoint requests deleted
// - Event subscriptions disposed
// - Expression caches cleared and compiled classes released
// - JDWP connection closed

@Test
void testResourceCleanup() {
    // Start session
    // Set breakpoints
    // Stop session
    // Verify no resource leaks
}
```

### Thread Safety Review
```java
// Review all shared state
// - Proper synchronization
// - No race conditions
// - No deadlocks

// Use concurrent collections
private final Map<String, IBreakpoint> breakpoints =
    new ConcurrentHashMap<>();
```

### Logging Review
```java
// Appropriate log levels
logger.fine("Processing breakpoint event");  // Debug
logger.info("Debug session started");        // Info
logger.warning("Breakpoint verification failed");  // Warning
logger.severe("JDWP connection failed");     // Error
```

### Tests
- Security review complete
- Error handling comprehensive
- Resource cleanup verified
- Thread safety verified
- Logging appropriate

**Acceptance**: Production-ready code

---

## Task 7.6: Documentation Finalization

**Time**: 8 hours

### API Documentation (JavaDoc)
```java
/**
 * Main service for Java debugging operations.
 *
 * <p>DebuggerService provides comprehensive debugging capabilities including:
 * <ul>
 *   <li>Breakpoint management (line, conditional, exception, method, data)</li>
 *   <li>Stepping operations (step in, over, out, continue, pause)</li>
 *   <li>Variable inspection and modification</li>
 *   <li>Expression evaluation</li>
 *   <li>Hot reload integration</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * DebuggerService service = new DebuggerService(context);
 * DebugSession session = service.startSession(
 *     DebugSessionConfig.defaultConfig()
 * ).join();
 *
 * List<Breakpoint> bps = service.setBreakpoints(
 *     "/path/to/File.java",
 *     List.of(new BreakpointSpec(42, "x > 10", null, null))
 * ).join();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe and can be called concurrently.
 *
 * <h2>Performance</h2>
 * <p>Most operations complete in <100ms. See performance benchmarks.
 *
 * @see DebugSession
 * @see BreakpointManager
 * @since 1.0
 */
public class DebuggerService {
    // ...
}
```

### User Guides
```markdown
# Descartes Debugger User Guide

## Introduction
Descartes includes a full-featured Java debugger accessible via MCP...

## Quick Start
1. Start Descartes MCP server
2. Connect Claude Desktop
3. Ask Claude: "Start a debug session"

## Features
### Breakpoints
Descartes supports all major breakpoint types...

### Stepping
Step through code execution...

### Variable Inspection
Inspect variable values at any point...

### Expression Evaluation
Evaluate Java expressions...

### Hot Reload
Modify code without restarting...

## Best Practices
- Set breakpoints at strategic points
- Use conditional breakpoints to reduce noise
- Use logpoints for non-intrusive debugging
- Use watch expressions for tracking state

## Troubleshooting
### Breakpoint not hitting
1. Verify class is loaded
2. Check breakpoint is verified
3. Ensure code path is executed

### Expression evaluation fails
1. Check expression syntax
2. Verify variables in scope
3. Check for side effects

## Performance Tips
- Limit number of simultaneous breakpoints (<100)
- Use pagination for large collections
- Enable async JDWP for remote debugging
```

### Architecture Documentation
```markdown
# Debugger Architecture

## Overview
The Descartes debugger uses JDWP (Java Debug Wire Protocol)...

## Components
### DebuggerService
Main service coordinating all debugging operations...

### EventHub
Reactive event processing using RxJava...

### BreakpointManager
Manages breakpoint lifecycle...

[Detailed architecture documentation]
```

### Tests
- All public APIs documented
- User guides complete
- Architecture documented
- Examples tested

**Acceptance**: Documentation complete and accurate

---

## Task 7.7: Release Preparation

**Time**: 6 hours

### Release Checklist
```markdown
# Descartes Debugger Release Checklist

## Code Quality
- [ ] All tests passing
- [ ] Code coverage >80%
- [ ] No compiler warnings
- [ ] No static analysis warnings
- [ ] Code review approved

## Documentation
- [ ] API documentation complete
- [ ] User guide complete
- [ ] Architecture docs complete
- [ ] README updated
- [ ] CHANGELOG created

## Testing
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Performance tests pass
- [ ] Manual testing complete
- [ ] Claude integration tested

## Performance
- [ ] Breakpoint hit <50ms
- [ ] Variable inspection <100ms
- [ ] Expression evaluation <50ms (simple), <500ms (complex)
- [ ] Step operations <100ms
- [ ] Stack trace <30ms
- [ ] Hot reload <1s

## Security
- [ ] Security review complete
- [ ] Risks documented
- [ ] Warnings in place

## Production
- [ ] Resource cleanup verified
- [ ] Thread safety verified
- [ ] Error handling comprehensive
- [ ] Logging appropriate
- [ ] No known critical bugs
```

### CHANGELOG.md
```markdown
# Changelog

## [1.0.0] - 2025-XX-XX

### Added
- Complete Java debugging support via MCP
- Breakpoints: line, conditional, exception, method, data
- Stepping: step in, over, out, continue, pause
- Variable inspection: locals, this, statics, fields, arrays
- Expression evaluation via JDI interpreter + Janino compiler
- Watch expressions with auto-evaluation
- Hot reload integration with breakpoint migration
- Logpoints for non-intrusive debugging
- Hit count filtering
- 10 MCP tools for debugging operations
- Event notifications for all debug events
- Comprehensive error handling and reporting
- Performance optimizations (async JDWP, caching, pagination)
- Extensive test coverage (>80%)

### Performance
- Breakpoint hit latency: ~35ms (target <50ms)
- Variable inspection: ~78ms for 100 vars (target <100ms)
- Expression evaluation: ~28ms simple, ~320ms complex (targets <50ms, <500ms)

### Documentation
- Complete API documentation (JavaDoc)
- User guides and debugging tutorials
- Architecture documentation
- Claude Code integration guide
- Performance benchmarks report
```

### Release Notes
```markdown
# Descartes Debugger v1.0.0

We're excited to announce the release of Descartes Debugger, bringing
full Java debugging capabilities to AI assistants via MCP.

## Highlights

🔍 **Comprehensive Debugging**
- Set breakpoints, step through code, inspect variables
- Conditional breakpoints, exception breakpoints, watchpoints
- Expression evaluation and watch expressions

🔥 **Hot Reload Integration**
- Modify code without restarting
- Breakpoints automatically migrate to new bytecode

⚡ **High Performance**
- <50ms breakpoint hit latency
- <100ms variable inspection
- Optimized for real-time debugging

🤖 **AI-First Design**
- 10 MCP tools designed for AI interaction
- Clear error messages and notifications
- Comprehensive documentation

## Getting Started

See [docs/debugger-tools.md](docs/debugger-tools.md) for complete guide.

## Known Limitations

- Expression compiler currently relies on Janino (limited Kotlin/lambda support); optional JDT integration planned
- Line number mapping after hot reload uses simple heuristics
- Virtual threads (Java 21+) not fully supported yet

## Feedback

Report issues at [GitHub Issues](https://github.com/...)
```

### Tests
- Release checklist complete
- CHANGELOG accurate
- Release notes clear

**Acceptance**: Ready for release

---

## Task 7.8: Final Validation

**Time**: 8 hours

### End-to-End Testing
```java
@Test
void testCompleteDebuggingWorkflow() {
    // Simulate real-world debugging scenario
    // 1. Application with bug
    // 2. Start debug session via MCP
    // 3. Set breakpoints
    // 4. Reproduce bug
    // 5. Inspect state
    // 6. Identify root cause
    // 7. Fix with hot reload
    // 8. Verify fix
    // 9. Continue debugging
    // 10. Stop session
}
```

### Claude Desktop Testing
```markdown
# Final Claude Desktop Testing

Complete debugging scenarios with actual Claude:

## Scenario 1: Debug NullPointerException
- [ ] Claude starts session
- [ ] Claude sets breakpoint
- [ ] Claude inspects variables
- [ ] Claude identifies null value
- [ ] Claude explains root cause
- [ ] Claude suggests fix

## Scenario 2: Debug Performance Issue
- [ ] Claude uses profiler to identify hotspot
- [ ] Claude sets breakpoint in slow method
- [ ] Claude inspects variables
- [ ] Claude suggests optimization
- [ ] Claude hot reloads fix
- [ ] Claude verifies improvement

## Scenario 3: Debug Multi-threaded Deadlock
- [ ] Claude lists all threads
- [ ] Claude identifies blocked threads
- [ ] Claude inspects thread states
- [ ] Claude analyzes lock ownership
- [ ] Claude explains deadlock
- [ ] Claude suggests solution
```

### Performance Validation
```bash
# Run all performance benchmarks
mvn test -P performance-tests

# Verify all targets met
# Generate final performance report
```

### Documentation Validation
```bash
# Verify all links work
# Test all code examples
# Verify examples match current API
```

### Tests
- End-to-end tests pass
- Claude testing successful
- Performance validated
- Documentation validated

**Acceptance**: Fully validated and ready

---

## Phase 7 Completion Checklist

- [ ] All tasks completed
- [ ] Unit test coverage >80%
- [ ] All integration tests passing
- [ ] Performance optimizations done
- [ ] All bugs fixed
- [ ] Production readiness verified
- [ ] Documentation complete
- [ ] Release prepared
- [ ] Final validation successful
- [ ] Code review approved
- [ ] **READY FOR PRODUCTION**

---

## Post-Release Activities

### Monitoring
- Watch for issues reported by users
- Monitor performance in production
- Collect feedback from Claude usage

### Iteration
- Address user feedback
- Fix newly discovered bugs
- Add requested features

### Future Enhancements
- Direct bytecode instrumentation mode
- Eclipse JDT evaluation engine
- Time-travel debugging
- Distributed debugging
- Advanced AI features

---

**End of Phase 7 Task Document**

---

# Descartes Debugger Implementation - Complete

All 7 phases documented with comprehensive task breakdowns totaling approximately 10 weeks of implementation work.

**Vision**: planning/debugger/vision.md (30,000+ words)
**Tasks**: planning/debugger/tasks/01-07-*.md (7 phases, 50+ tasks)

**Next Steps**: Begin Phase 1 implementation

---

## CORRECTIONS AND ADDITIONS: Testing Infrastructure Specifications

### DebuggerTestBase - Complete Implementation Specification

The planning mentions DebuggerTestBase but doesn't specify implementation. Here's the complete specification:

```java
package com.bitsapplied.descartes.debugger.testutils;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Base class for debugger integration tests.
 * Provides utilities for launching test JVMs with JDWP enabled.
 */
public abstract class DebuggerTestBase {
    
    /**
     * Test JVM process with JDWP debugging enabled.
     * Implements AutoCloseable for automatic cleanup.
     */
    protected static class TestJVM implements AutoCloseable {
        private final Process process;
        private final int debugPort;
        private final String mainClass;
        
        private TestJVM(Process process, int debugPort, String mainClass) {
            this.process = process;
            this.debugPort = debugPort;
            this.mainClass = mainClass;
        }
        
        /**
         * Launch a new JVM for testing with JDWP enabled.
         * 
         * @param mainClass The main class to run
         * @param suspend Whether to suspend on startup (usually true for tests)
         * @param timeout How long to wait for JDWP port to become available
         * @return TestJVM instance
         * @throws IOException if launch fails
         */
        public static TestJVM launch(Class<?> mainClass, boolean suspend, Duration timeout) 
                throws IOException, InterruptedException {
            // 1. Find free port for JDWP
            int port = findFreePort();
            
            // 2. Build command to launch JVM
            String javaHome = System.getProperty("java.home");
            String classpath = System.getProperty("java.class.path");
            String className = mainClass.getName();
            
            ProcessBuilder pb = new ProcessBuilder(
                javaHome + "/bin/java",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + (suspend ? "y" : "n") + ",address=127.0.0.1:" + port,
                "-cp", classpath,
                className
            );
            
            // Inherit IO for visibility during tests
            pb.inheritIO();
            
            // 3. Start process
            Process process = pb.start();
            
            // 4. Wait for JDWP port to become available
            if (!waitForDebugPort(port, timeout)) {
                process.destroyForcibly();
                throw new IOException("JDWP port " + port + " did not become available within " + timeout);
            }
            
            return new TestJVM(process, port, className);
        }
        
        public int getDebugPort() {
            return debugPort;
        }
        
        public boolean isAlive() {
            return process.isAlive();
        }
        
        public void waitFor(Duration timeout) throws InterruptedException {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        
        @Override
        public void close() {
            // Graceful shutdown attempt
            process.destroy();
            
            try {
                // Wait up to 5 seconds for graceful shutdown
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    // Force kill if still alive
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        
        /**
         * Find an available port for JDWP.
         */
        private static int findFreePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            }
        }
        
        /**
         * Wait for JDWP port to become available.
         * 
         * @param port The port to check
         * @param timeout How long to wait
         * @return true if port became available, false if timeout
         */
        private static boolean waitForDebugPort(int port, Duration timeout) {
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            
            while (System.currentTimeMillis() < deadline) {
                try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
                    // Connection successful - port is open
                    return true;
                } catch (IOException e) {
                    // Port not yet available, wait and retry
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            
            return false;  // Timeout
        }
    }
    
    /**
     * Utility to run a test with a managed test JVM.
     * Automatically launches, waits, and cleans up.
     */
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

### SimpleTestApplication - Complete Specification

```java
package com.bitsapplied.descartes.debugger.testutils;

/**
 * Simple test application for debugger integration tests.
 * Provides predictable execution for breakpoints, stepping, and variable inspection.
 */
public class SimpleTestApplication {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("SimpleTestApplication started");
        
        SimpleTestApplication app = new SimpleTestApplication();
        
        // Run test scenarios
        app.countingLoop();
        app.methodCalls();
        app.exceptionScenario();
        
        System.out.println("SimpleTestApplication finished");
    }
    
    /**
     * Simple counting loop for breakpoint testing.
     * Breakpoint targets: lines with counter++
     */
    public void countingLoop() {
        int counter = 0;
        for (int i = 0; i < 10; i++) {
            counter++;  // BREAKPOINT TARGET: line 28
            System.out.println("Counter: " + counter);
        }
    }
    
    /**
     * Method call chain for step-into/step-over testing.
     */
    public void methodCalls() {
        int result = add(5, 3);  // STEP-OVER TARGET
        result = multiply(result, 2);  // STEP-INTO TARGET
        System.out.println("Result: " + result);
    }
    
    private int add(int a, int b) {
        return a + b;  // STEP TARGET
    }
    
    private int multiply(int a, int b) {
        int result = a * b;  // BREAKPOINT TARGET: line 48
        return result;
    }
    
    /**
     * Exception scenario for exception breakpoint testing.
     */
    public void exceptionScenario() {
        try {
            throwException();
        } catch (IllegalArgumentException e) {
            System.out.println("Caught exception: " + e.getMessage());
        }
    }
    
    private void throwException() {
        throw new IllegalArgumentException("Test exception");  // EXCEPTION BREAKPOINT TARGET
    }
    
    /**
     * Variable inspection scenario with different types.
     */
    public void variableInspection() {
        int primitiveInt = 42;
        String stringVar = "Hello, Debugger!";
        int[] arrayVar = {1, 2, 3, 4, 5};
        Person objectVar = new Person("Alice", 30);
        
        System.out.println("Variables initialized");  // BREAKPOINT TARGET for variable inspection
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

### Usage Example in Tests

```java
package com.bitsapplied.descartes.debugger.integration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BreakpointIntegrationTest extends DebuggerTestBase {
    
    @Test
    public void testLineBreakpointHit() throws Exception {
        withTestJVM(SimpleTestApplication.class, true, testJVM -> {
            // 1. Start debug session with test JVM port
            DebuggerService debugger = new DebuggerService(context);
            debugger.startSession(DebugSessionConfig.forPort(testJVM.getDebugPort()));
            
            // 2. Set breakpoint at line 28 (counter++)
            BreakpointSpec breakpoint = new BreakpointSpec()
                .setSource("SimpleTestApplication.java")
                .setLine(28);
            
            debugger.setBreakpoint(breakpoint);
            
            // 3. Resume and wait for breakpoint hit
            debugger.resume();
            
            // 4. Wait for suspension event
            BreakpointEvent event = debugger.waitForBreakpoint(Duration.ofSeconds(5));
            assertNotNull(event, "Breakpoint should be hit");
            
            // 5. Verify location
            assertEquals(28, event.location().lineNumber());
            
            // 6. Clean up
            debugger.stopSession();
        });
    }
}
```

### Test Strategy - Clarifications

**Unit Tests vs Integration Tests**:
- **Unit Tests**: Test individual components with mocks
  - BreakpointManager with mock VM
  - EventHub with synthetic events
  - Expression evaluator with isolated contexts
  
- **Integration Tests**: Test with real TestJVM processes
  - Full debugging sessions
  - Real JDWP connections
  - Actual bytecode manipulation

**Concurrency Test Profile** (already exists):
```bash
mvn test -Pconcurrency-tests
```

**Debugger Test Profile** (add to pom.xml):
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
            <include>**/debugger/integration/*Test.java</include>
          </includes>
          <systemPropertyVariables>
            <jdk.attach.allowAttachSelf>true</jdk.attach.allowAttachSelf>
          </systemPropertyVariables>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

Run with: `mvn test -Pdebugger-tests`

---

**End of Phase 7 Task Document with Testing Infrastructure Specifications**
