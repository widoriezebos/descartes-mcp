# Phase 4: Expression Evaluation

**Timeline**: Week 6  
**Status**: Not Started  
**Priority**: P1 (High)  
**Dependencies**: Phase 3 Complete

---

## Overview

Build a first-class expression evaluation pipeline that runs entirely through JDI. The evaluator must:
- Parse Java expressions against the suspended stack frame
- Resolve locals, fields, and static members without resuming the thread
- Invoke instance/static methods via JDI
- Optionally compile complex expressions (loops, lambdas) with a lightweight compiler (Janino or Eclipse JDT) and execute them inside the target VM
- Support watch expressions, history, and caching with deterministic cleanup
- Continue using the existing `JShellService`/`JShellTool` solely for the interactive REPL; no duplicate JShell bridge is introduced

**Success Criteria**
- Evaluate simple expressions (`x + y`, `user.name`) in <50 ms
- Evaluate compiled expressions (`orders.stream().filter(...).count()`) in <500 ms
- Invoke methods (instance/static) correctly, with side–effect warnings
- Watch expressions re-evaluate on every stop
- Clear diagnostics (syntax, type, runtime, timeout)

---

## Task 4.1: Expression Evaluation Core

**Time**: 12 hours  
**Dependencies**: Phase 2 conditional breakpoint evaluator skeleton

### Implement ExpressionEvaluator
```java
public interface ExpressionEvaluator {
    CompletableFuture<Value> evaluate(String expression,
                                      ThreadReference thread,
                                      StackFrame frame,
                                      EvaluationContext context);
}
```

### Build Parsing Layer
- Implement `ExpressionParser` using a lightweight Pratt parser (support identifiers, literals, arithmetic, logical ops, ternary, method calls, array access).
- Produce an AST (`ExpressionNode` hierarchy) annotated with source spans for diagnostics.

### Implement Interpreter
- `ExpressionInterpreter.evaluate(ExpressionNode, EvaluationState)` walks the AST and performs operations through JDI APIs:
  - Local variables via `StackFrame.getValue`
  - Fields via `ObjectReference.getValue` / `ReferenceType.getValue`
  - Array access via `ArrayReference`
  - Arithmetic/comparison via primitive operations (`ValueUtils` helper).
- All evaluation happens on the single-threaded `debuggerExecutor` while the thread stays suspended.

### Error Handling
- Syntax errors → `DebuggerErrorCode.EVALUATION_SYNTAX_ERROR`
- Unsupported constructs → `DebuggerErrorCode.EVALUATION_UNSUPPORTED`
- Type mismatches → `DebuggerErrorCode.EVALUATION_TYPE_MISMATCH`

### Tests
- Parse/evaluate literals, arithmetic, comparisons, logical operators.
- Resolve locals (`count > 5`) and `this` fields (`this.status == Status.OPEN`).
- Access static fields (`Config.TIMEOUT_SECONDS`).
- Diagnostics include column information.

**Acceptance**
- Direct interpreter handles 90% of debugger expressions without compiling new bytecode.

---

## Task 4.2: Compiled Expression Engine

**Time**: 14 hours  
**Dependencies**: Task 4.1

### Compiler Integration
- Add Janino (preferred for footprint) or Eclipse JDT Core to `pom.xml`.
- Implement `CompiledExpressionManager` that:
  - Wraps user expression in a synthetic class (`DebugExpressionN`) with a static method.
  - Generates source with bindings for locals/`this`/statics (passed as parameters).
  - Compiles to bytecode using Janino/JDT.
  - Defines the class in the target VM via `ReferenceType.classLoader().loadClass` or `VirtualMachine.classesByName` + `ClassLoaderReference`. For self-debugging (same VM), use in-process `Instrumentation` helper.
- Provide `CompiledExpression` objects capturing bytecode, loaded `ReferenceType`, and invocation `Method`.

### Invocation Path
- On evaluation request:
  1. Check interpreter coverage; if AST contains unsupported constructs, fallback to compiler.
  2. Prepare argument list by mirroring `Value`s into an array.
  3. Use `ClassType.invokeMethod` or `ObjectReference.invokeMethod` to execute compiled method with `ObjectReference.INVOKE_SINGLE_THREADED`.
  4. Convert return `Value` back to debugger format.

### Caching
- Cache compiled expressions per thread + expression text.
- Invalidate/clear cache on thread death, hot reload, or class redefinition.

### Tests
- Compile/evaluate expressions with:
  - Method chains, streams, lambdas.
  - New object creation (`new BigDecimal("42.0")`).
  - Static imports (simulated via helper methods).
- Verify compiled code sees same class loader as debuggee method.
- Ensure repeated evaluations reuse compiled class/method.

**Acceptance**
- Expressions beyond interpreter coverage compile once, run fast, and respect suspension semantics.

---

## Task 4.3: Method Invocation & Side Effects

**Time**: 8 hours  
**Dependencies**: Task 4.1

### MethodInvoker Enhancements
```java
public class MethodInvoker {
    CompletableFuture<Value> invoke(ObjectReference target,
                                    MethodDescriptor descriptor,
                                    List<Value> arguments,
                                    ThreadReference thread,
                                    boolean allowSideEffects);
}
```

- Support overload resolution (match by signature, parameter count, boxing).
- Handle static methods (`ClassType.invokeMethod`).
- Surface side-effect warning in responses; allow configuration to block certain packages (`java.lang.Runtime`, etc.).
- Respect `INVOKE_SINGLE_THREADED` and timeout (default 2s).

### Tests
- Invoke getters/setters, static utility methods, chained invocations.
- Method throws exception → propagate `DebuggerErrorCode.EVALUATION_RUNTIME_EXCEPTION` with message + stack.
- Timeout test (method sleeps >2s) → cancel invocation and raise timeout error.

**Acceptance**
- Method invocation reusable by interpreter, compiler, and watch manager.

---

## Task 4.4: Evaluation Context & Caching

**Time**: 6 hours  
**Dependencies**: Tasks 4.1–4.3

### EvaluationContext
```java
public record EvaluationContext(
    DebugSession session,
    BreakpointManager breakpointManager,
    ExpressionCache cache,
    long threadId
) {}
```

- Tracks current thread, frame depth, active compiled expressions, and interpreter settings.
- Maintains `ValueFactory` helper for creating new primitive/object values via `VirtualMachine.mirrorOf`.

### ExpressionCache
- Map `<ThreadId, ExpressionCacheEntry>` storing AST → compiled expression.
- Track metrics (hit rate, compile time).
- Clear cache on:
  - Thread death (subscribe to EventHub).
  - Hot reload (phase 5).
  - Debug session stop.

### Tests
- Cache hit/miss scenarios.
- Clearing cache after thread exit/hot reload.
- Prevent stale references (no `InvalidStackFrame` when reusing cached compiled code).

**Acceptance**
- Cache integrates cleanly with session lifecycle.

---

## Task 4.5: Watch Expression Manager

**Time**: 6 hours  
**Dependencies**: Tasks 4.1–4.4

### WatchExpressionManager
```java
public class WatchExpressionManager {
    String addWatch(String expression);
    void removeWatch(String id);
    CompletableFuture<List<WatchResult>> evaluateAll(ThreadReference thread, int depth);
}
```

- Store watches alongside parsed AST/compiled representation to avoid repeated work.
- Evaluate on every `StoppedEvent` using the same evaluator pipeline.
- Emit diagnostics (`success`, `value`, `errorMessage`, `evaluationTimeMs`).
- Provide configuration for per-watch timeout.

### Tests
- Add/remove watch.
- Evaluate after breakpoint/step.
- Watch that compiles once and reuses compiled class.
- Watch hitting timeout → returns error but does not resume thread.

**Acceptance**
- Watches piggyback on primary evaluator without JShell.

---

## Task 4.6: MCP Tooling

**Time**: 6 hours  
**Dependencies**: Task 4.5

### DebuggerEvaluateTool
- Input: `expression`, `frameId`, optional `timeoutMs`, `allowSideEffects`.
- Output: `value`, `type`, `variablesReference`, `sideEffects`.
- On timeout → return error with guidance.

### DebuggerWatchTool
- Operations: `add`, `remove`, `list`.
- Notifications include evaluation time and any compiler warnings.

### Tests
- Evaluate simple/compiled expressions via tool.
- Add watch, step, ensure notification arrives.
- Side-effect blocked scenario returns actionable error.

**Acceptance**
- Expression tools expose the new evaluator cleanly through MCP.

---

## Task 4.7: Integration & Performance Testing

**Time**: 6 hours  
**Dependencies**: Tasks 4.1–4.6

### Integration Scenarios
```java
@Test
void testExpressionEvaluationEndToEnd() {
    // Start session, set breakpoint, hit stop
    // Evaluate interpreter-friendly expression
    // Evaluate compiled expression with streams
    // Add watch, step, ensure re-evaluation
    // Invoke method with side effects toggle
}
```

### Performance Benchmarks
- Interpreter expression (<50 ms median).
- Compiled expression after warm cache (<100 ms).
- Compile time for first run (<400 ms).
- Memory usage of cache vs baseline.

### Error Scenarios
- Syntax error.
- Type mismatch.
- Runtime exception (NPE inside expression).
- Timeout.

**Acceptance**
- All integration tests pass; performance targets met with cache warmed.

---

## Phase 4 Completion Checklist

- [ ] Interpreter handles arithmetic, logical, field/array access
- [ ] Compiler integration supported and configurable (Janino/JDT)
- [ ] Method invocation reusable across debugger features
- [ ] Expression/watch caches clear on lifecycle events
- [ ] MCP tools updated with evaluator
- [ ] Unit tests >80% coverage for evaluator modules
- [ ] Integration + performance benchmarks pass
- [ ] Documentation updated (README/TOOLS)
- [ ] Code review complete

---

## Alternatives Considered

1. **Full Eclipse JDT Debug Evaluation Engine**  
   - Pros: battle-tested, feature-rich.  
   - Cons: heavier dependency footprint, higher integration complexity.

2. **Janino-only approach**  
   - Pros: small, fast compile times.  
   - Cons: limited language feature set (no generics/lambdas without extra wiring).

Decision: start with interpreter + Janino. If advanced scenarios demand more features, add optional Eclipse JDT engine behind a feature flag.

---

**End of Phase 4 Task Document**

---

## CORRECTIONS AND ADDITIONS (Applied During Planning Review)

### Task 4.2: Janino Compiler Integration - Detailed Specification

The planning mentions using Janino but lacks integration details. Here's the complete specification:

#### JDI-Aware ClassLoader for Janino

Janino needs access to classes from the target JVM. Implement a custom classloader:

```java
public class JDIClassLoader extends ClassLoader {
    private final VirtualMachine vm;
    private final Map<String, byte[]> bytecodeCache = new ConcurrentHashMap<>();
    
    public JDIClassLoader(VirtualMachine vm) {
        super(JDIClassLoader.class.getClassLoader());
        this.vm = vm;
    }
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Check cache first
        byte[] bytecode = bytecodeCache.get(name);
        
        if (bytecode == null) {
            // Load from target JVM via JDI
            List<ReferenceType> types = vm.classesByName(name);
            if (types.isEmpty()) {
                throw new ClassNotFoundException(name);
            }
            
            ReferenceType type = types.get(0);
            bytecode = type.bytecodes();  // Get bytecode from JDI
            bytecodeCache.put(name, bytecode);
        }
        
        return defineClass(name, bytecode, 0, bytecode.length);
    }
    
    public void invalidateCache() {
        bytecodeCache.clear();
    }
}
```

#### Janino Evaluator Implementation

```java
public class JaninoEvaluator implements IEvaluationProvider {
    private final JDIClassLoader classLoader;
    private final ExpressionCache cache;
    
    @Override
    public CompletableFuture<Value> evaluate(String expression, 
                                             ThreadReference thread, 
                                             StackFrame frame) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Extract variable context from stack frame
                Map<String, LocalVariable> locals = frame.visibleVariables().stream()
                    .collect(Collectors.toMap(LocalVariable::name, v -> v));
                
                // 2. Check cache
                CacheKey key = new CacheKey(expression, locals.keySet(), 
                                           locals.values().stream()
                                               .map(v -> v.typeName())
                                               .toList());
                CompiledExpression compiled = cache.get(key);
                
                if (compiled == null) {
                    // 3. Compile with Janino
                    compiled = compileExpression(expression, locals);
                    cache.put(key, compiled);
                }
                
                // 4. Evaluate compiled expression
                Object[] variableValues = locals.keySet().stream()
                    .map(name -> convertJDIValueToJava(frame.getValue(locals.get(name))))
                    .toArray();
                
                Object result = compiled.evaluate(variableValues);
                
                // 5. Convert Java result back to JDI Value
                return convertJavaToJDIValue(result, thread.virtualMachine());
                
            } catch (Exception e) {
                throw new DebuggerException(
                    DebuggerErrorCode.EVALUATION_FAILED,
                    "Janino compilation failed: " + e.getMessage(),
                    e
                );
            }
        }, debuggerExecutor);
    }
    
    private CompiledExpression compileExpression(String expression, 
                                                 Map<String, LocalVariable> locals) 
            throws CompileException {
        IExpressionEvaluator evaluator = new ExpressionEvaluator();
        
        // Set parent classloader to JDI-aware loader
        evaluator.setParentClassLoader(classLoader);
        
        // Configure variable types
        String[] paramNames = locals.keySet().toArray(new String[0]);
        Class<?>[] paramTypes = locals.values().stream()
            .map(v -> jdiTypeToJavaClass(v.typeName()))
            .toArray(Class<?>[]::new);
        
        evaluator.setParameters(paramNames, paramTypes);
        
        // Set return type (Object for flexibility)
        evaluator.setExpressionType(Object.class);
        
        // Compile
        evaluator.cook(expression);
        
        return new CompiledExpression(evaluator);
    }
    
    private Class<?> jdiTypeToJavaClass(String typeName) {
        // Map JDI type names to Java classes
        return switch (typeName) {
            case "int" -> int.class;
            case "long" -> long.class;
            case "boolean" -> boolean.class;
            case "double" -> double.class;
            // ... other primitives
            default -> {
                try {
                    return classLoader.loadClass(typeName);
                } catch (ClassNotFoundException e) {
                    yield Object.class;  // Fallback
                }
            }
        };
    }
}
```

#### Version and Dependency

```xml
<dependency>
  <groupId>org.codehaus.janino</groupId>
  <artifactId>janino</artifactId>
  <version>3.1.11</version>  <!-- JDK 11+ compatible -->
</dependency>
```

**Important**: Janino 3.1.x supports JDK 11+. Earlier versions (2.7.x) only support JDK 8.

---

### Task 4.3: Expression Caching - Lifecycle Specification

Expression compilation is expensive (~10-50ms). Implement smart caching:

```java
public class ExpressionCache {
    // Cache key: expression source + variable types (not values!)
    public record CacheKey(
        String expression,
        Set<String> variableNames,
        List<String> variableTypes
    ) {
        // variableTypes order matches variableNames order
    }
    
    // Use WeakReference for compiled expressions to allow GC under memory pressure
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
                // Weak reference was cleared - remove entry
                cache.remove(key);
            }
        }
        misses.incrementAndGet();
        return null;
    }
    
    public void put(CacheKey key, CompiledExpression expression) {
        cache.put(key, new WeakReference<>(expression));
    }
    
    // Called when classes are hot-reloaded
    public void invalidate() {
        cache.clear();
        logger.info("Expression cache cleared (hot reload detected)");
    }
    
    // Called on specific class hot reload
    public void invalidateClass(String className) {
        cache.entrySet().removeIf(entry -> 
            entry.getKey().variableTypes().stream()
                .anyMatch(type -> type.startsWith(className))
        );
    }
    
    public Map<String, Object> getStats() {
        return Map.of(
            "size", cache.size(),
            "hits", hits.get(),
            "misses", misses.get(),
            "hitRate", hits.get() / (double) (hits.get() + misses.get())
        );
    }
}
```

**Cache Invalidation Strategy**:
1. **Hot Reload**: Subscribe to `HotReloadCompletionEvent` and call `invalidateClass(reloadedClass)`
2. **Thread Termination**: Clear any thread-specific cache entries
3. **Memory Pressure**: WeakReference allows GC to reclaim compiled expressions
4. **Session End**: Full `invalidate()` on debugger session stop

**Integration with Hot Reload** (Phase 5):
```java
// In DebuggerService initialization
hotReloadService.getEventBus()
    .subscribe(HotReloadCompletionEvent.class, event -> 
        CompletableFuture.runAsync(() -> {
            for (String className : event.reloadedClassNames()) {
                expressionCache.invalidateClass(className);
                jdiClassLoader.invalidateCache();  // Clear JDI bytecode cache too
            }
        }, debuggerExecutor)
    );
```

---

### Task 4.4: JShell Fallback - Implementation Specification

When Janino fails (complex expressions, lambdas, streams), fall back to JShell:

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
                logger.info("Janino evaluation failed, falling back to JShell: " + 
                           janinoError.getMessage());
                
                // Fallback to JShell (slower but more capable)
                try {
                    return jshellEvaluator.evaluate(expression, thread, frame).join();
                } catch (Exception jshellError) {
                    throw new DebuggerException(
                        DebuggerErrorCode.EVALUATION_FAILED,
                        "Both Janino and JShell evaluation failed. " +
                        "Janino: " + janinoError.getMessage() + "; " +
                        "JShell: " + jshellError.getMessage()
                    );
                }
            });
    }
}
```

**JShell Evaluator Integration**:
```java
public class JShellEvaluator implements IEvaluationProvider {
    private final JShell jshell;
    
    public JShellEvaluator() {
        // Reuse existing JShell infrastructure from JShellTool
        this.jshell = JShell.builder()
            .executionEngine("local")  // Local execution
            .build();
    }
    
    @Override
    public CompletableFuture<Value> evaluate(String expression, 
                                             ThreadReference thread, 
                                             StackFrame frame) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Import variables into JShell context
                Map<String, LocalVariable> locals = frame.visibleVariables().stream()
                    .collect(Collectors.toMap(LocalVariable::name, v -> v));
                
                for (Map.Entry<String, LocalVariable> entry : locals.entrySet()) {
                    String name = entry.getKey();
                    Value value = frame.getValue(entry.getValue());
                    String typeName = entry.getValue().typeName();
                    
                    // Define variable in JShell
                    jshell.eval(typeName + " " + name + " = (" + typeName + ") " + 
                               convertValueToJavaLiteral(value) + ";");
                }
                
                // 2. Evaluate expression
                List<SnippetEvent> events = jshell.eval(expression);
                SnippetEvent event = events.get(0);
                
                if (event.status() != Snippet.Status.VALID) {
                    throw new DebuggerException(
                        DebuggerErrorCode.EVALUATION_FAILED,
                        "JShell evaluation failed: " + jshell.diagnostics(event.snippet())
                            .map(Diag::getMessage)
                            .collect(Collectors.joining("; "))
                    );
                }
                
                // 3. Convert result back to JDI Value
                String resultValue = event.value();
                return convertJShellResultToJDIValue(resultValue, thread.virtualMachine());
                
            } catch (Exception e) {
                throw new DebuggerException(
                    DebuggerErrorCode.EVALUATION_FAILED,
                    "JShell evaluation error: " + e.getMessage(),
                    e
                );
            }
        }, debuggerExecutor);
    }
}
```

**Fallback Decision Logic**:
- **Use Janino**: Simple expressions (arithmetic, field access, method calls, ternary)
- **Use JShell**: Complex expressions (lambdas, streams, method references, try-catch)
- **Detection**: Try Janino first; on CompileException, fallback to JShell

---

### Summary of Phase 4 Corrections:

1. ✅ **Janino Integration**: Complete JDI-aware classloader implementation
2. ✅ **Variable Mapping**: JDI types → Java classes → Janino parameters
3. ✅ **Caching Strategy**: WeakReference cache with hot reload integration
4. ✅ **Cache Invalidation**: Per-class invalidation on hot reload
5. ✅ **JShell Fallback**: Automatic fallback with error aggregation
6. ✅ **Dependency Version**: Janino 3.1.11 for JDK 11+ compatibility

**Implementation Priority**:
- Phase 4.1-4.2: Interpreter + Janino (as planned)
- Phase 4.3: Caching with WeakReference
- Phase 4.4: JShell fallback
- Phase 5: Hot reload integration

---

**End of Phase 4 Task Document with Corrections**
