# Phase 3: Variable Inspection

**Timeline**: Week 5
**Status**: Not Started
**Priority**: P0 (Blocking)
**Dependencies**: Phase 2 Complete

---

## Overview

Implement comprehensive variable inspection:
- Extract variables from stack frames (locals, this, statics)
- Variable formatting system with type-specific formatters
- Lazy loading for complex objects
- Pagination for large collections
- Variable modification support

**Success Criteria**:
- Can inspect all variable scopes
- Complex objects expand correctly
- Large arrays/collections don't timeout
- Variable modification works
- Performance <100ms for 100 variables

> **Concurrency Note**: every read/write must occur while the target thread remains suspended. Route debugger work through a single-threaded `debuggerExecutor` (fed by the event hub) rather than the common ForkJoin pool.

---

## Task 3.1: Variable Extraction

**Time**: 8 hours

### Implement VariableExtractor
```java
public class VariableExtractor {
    // extractLocalVariables(StackFrame) → List<Variable>
    // extractThisObject(StackFrame) → Variable
    // extractStaticFields(StackFrame) → List<Variable>
    // extractFieldVariables(ObjectReference) → List<Variable>
    // extractArrayElements(ArrayReference, start, count) → List<Variable>
}
```

### Variable Model
```java
public class Variable {
    private final String name;
    private final Value value;
    private final LocalVariable local;    // If local variable
    private final Field field;            // If field
    private int variablesReference;       // 0 = primitive, >0 = complex

    // Getters, setters
}
```

### Pagination Support
```java
// Bulk fetch to avoid JDWP timeout
public static void bulkFetchValues(List<Field> fields,
                                   int limitPerRequest,
                                   Consumer<List<Field>> processor) {
    for (int i = 0; i < fields.size(); i += limitPerRequest) {
        List<Field> page = fields.subList(i,
            Math.min(i + limitPerRequest, fields.size()));
        processor.accept(page);
    }
}
```

### Tests
- Extract local variables
- Extract this object
- Extract static fields
- Extract array elements
- Pagination for large objects
- Handle missing debug info (AbsentInformationException)

**Acceptance**: Can extract all variable types from frames

---

## Task 3.2: Variable Formatting System

**Time**: 8 hours

### Create IValueFormatter Interface
```java
public interface IValueFormatter {
    boolean acceptType(Type type, Map<String, Object> options);
    String toString(Object value, Map<String, Object> options);
    Value valueOf(VirtualMachine vm, String valueString, Type type, Map<String, Object> options);
    int getPriority();  // Higher priority formatters checked first
}
```

### Implement Type-Specific Formatters
1. **NumericFormatter** - int, long, float, double (hex/decimal)
2. **BooleanFormatter** - true/false
3. **CharacterFormatter** - char with escapes
4. **StringObjectFormatter** - Strings with truncation
5. **ArrayObjectFormatter** - Arrays with size
6. **ObjectFormatter** - Generic objects with type
7. **NullObjectFormatter** - null values

### VariableFormatter Registry
```java
public class VariableFormatter {
    private final Map<IValueFormatter, Integer> valueFormatters;

    public void registerValueFormatter(IValueFormatter formatter, int priority);

    public String valueToString(Value value, Map<String, Object> options) {
        // Find highest priority formatter that accepts type
        // Format value using that formatter
    }

    public Value stringToValue(VirtualMachine vm,
                               String valueString,
                               Type type,
                               Map<String, Object> options) {
        // Parse string back to JDI Value
    }
}
```

### Formatting Options
```java
Map<String, Object> options = Map.of(
    "showQualifiedNames", false,
    "maxStringLength", 100,
    "numericFormat", "decimal",  // or "hex"
    "showToString", true
);
```

### Tests
- Each formatter with various values
- Priority-based selection
- Format options work
- Round-trip (string→value→string)

**Acceptance**: All value types format correctly

> Always build new `Value` instances through the `VirtualMachine` provided to the formatter (`mirrorOf`, `mirrorOfArray`, etc.); direct constructors are not available in JDI.

---

## Task 3.3: Variable Reference System

**Time**: 6 hours

### Implement VariableReferencePool
```java
public class VariableReferencePool {
    private final Map<Integer, Object> idToObject;
    private final AtomicInteger nextId;

    public int create(Object object) {
        int id = nextId.incrementAndGet();
        idToObject.put(id, object);
        return id;
    }

    public Object getObjectById(int id) {
        return idToObject.get(id);
    }

    public void release(int id) {
        idToObject.remove(id);
    }
}
```

### Variable Expansion
```java
public class VariableExpander {
    // expandVariable(variableReference) → List<Variable>
    // For objects: return fields
    // For arrays: return elements
    // For collections: return logical structure
}
```

### Tests
- Create and retrieve references
- Expand objects
- Expand arrays
- Reference cleanup

**Acceptance**: Complex objects can be expanded recursively

---

## Task 3.4: Lazy Loading

**Time**: 4 hours

### Lazy Variable Detection
```java
public class LazyVariableDetector {
    public boolean isLazyLoadingSupported(Value value) {
        // Collections, Maps, objects with toString()
        if (value instanceof ObjectReference) {
            return isCollection(value) ||
                   isMap(value) ||
                   hasToStringMethod(value);
        }
        return false;
    }
}
```

### Variable Presentation Hints
```java
public class VariableInfo {
    private String presentationHint;  // "lazy"

    // First expansion: show toString()
    // Second expansion: show actual fields
}
```

### Tests
- Detect lazy-loadable types
- First expansion returns summary
- Second expansion returns details

**Acceptance**: Large objects load lazily

---

## Task 3.5: Scopes System

**Time**: 4 hours

### Implement ScopesHandler
```java
public class ScopesHandler {
    public List<Scope> getScopes(StackFrame frame) {
        List<Scope> scopes = new ArrayList<>();

        // Local scope
        scopes.add(new Scope(
            "Local",
            createLocalVariablesReference(frame)
        ));

        // This scope (if non-static method)
        ObjectReference thisObject = frame.thisObject();
        if (thisObject != null) {
            scopes.add(new Scope(
                "This",
                createThisReference(thisObject)
            ));
        }

        // Static scope (if enabled)
        if (settings.showStaticVariables()) {
            scopes.add(new Scope(
                "Static",
                createStaticVariablesReference(frame)
            ));
        }

        return scopes;
    }
}

public class Scope {
    private final String name;
    private final int variablesReference;
    private final boolean expensive;  // Hint for lazy loading
}
```

### Tests
- Get scopes for static method (no this)
- Get scopes for instance method (has this)
- Static scope visibility

**Acceptance**: Scopes provide logical variable grouping

---

## Task 3.6: Variable Modification

**Time**: 6 hours

### Implement VariableModifier
```java
public class VariableModifier {
    private final VirtualMachine vm;
    private final VariableFormatter formatter;

    public VariableModifier(VirtualMachine vm, VariableFormatter formatter) {
        this.vm = vm;
        this.formatter = formatter;
    }

    public Value setValue(String name, String newValueString,
                         StackFrame frame, Map<String, Object> options) {
        // Find variable (local, field, static)
        LocalVariable local = findLocalVariable(frame, name);
        if (local != null) {
            Value newValue = formatter.stringToValue(
                vm, newValueString, local.type(), options);
            frame.setValue(local, newValue);
            return newValue;
        }

        // Try fields
        // Try statics
    }

    public Value setArrayElement(ArrayReference array, int index,
                                String newValueString,
                                Map<String, Object> options) {
        Type elementType = ((ArrayType) array.type()).componentType();
        Value newValue = formatter.stringToValue(
            vm, newValueString, elementType, options);
        array.setValue(index, newValue);
        return newValue;
    }

    public Value setField(ObjectReference object, String fieldName,
                         String newValueString,
                         Map<String, Object> options) {
        Field field = object.referenceType().fieldByName(fieldName);
        Value newValue = formatter.stringToValue(
            vm, newValueString, field.type(), options);

        if (field.isStatic()) {
            object.referenceType().setValue(field, newValue);
        } else {
            object.setValue(field, newValue);
        }

        return newValue;
    }
}
```

### Tests
- Set local variable
- Set field
- Set array element
- Set static field
- Invalid value handling

**Acceptance**: Can modify variable values

---

## Task 3.7: MCP Tool - debugger_variables

**Time**: 6 hours

### Implement DebuggerVariablesTool
```java
public class DebuggerVariablesTool implements MCPTool {
    // Operations: get, set
    // Support scopes, pagination, expansion
}
```

### Input Schema
```json
{
  "operation": "get",
  "frameId": 1001,
  "scope": "all",  // or "local", "this", "static"
  "variableReference": null,  // For expansion
  "start": 0,
  "count": 100
}
```

### Response Format
```json
{
  "success": true,
  "variables": [
    {
      "name": "x",
      "value": "42",
      "type": "int",
      "variablesReference": 0
    },
    {
      "name": "obj",
      "value": "MyClass@7f3b84b8",
      "type": "com.example.MyClass",
      "variablesReference": 2001
    }
  ]
}
```

### Set Variable Schema
```json
{
  "operation": "set",
  "frameId": 1001,
  "name": "x",
  "value": "100"
}
```

### Tests
- Get variables for all scopes
- Pagination works
- Variable expansion
- Set variable value
- Error handling

**Acceptance**: Variable inspection works via MCP

---

## Task 3.8: Performance Optimization

**Time**: 6 hours

### Async Variable Fetching
```java
public CompletableFuture<List<Variable>> getVariablesAsync(int frameId) {
    StackFrame frame = getFrame(frameId);  // Only valid while suspended

    return CompletableFuture.supplyAsync(() -> {
        List<Variable> locals = extractLocalVariables(frame);
        Variable thisVar = extractThisObject(frame);
        List<Variable> statics = extractStaticFields(frame);
        return combineResults(locals, thisVar, statics);
    }, debuggerExecutor);  // single-threaded executor bound to debugger thread
}
```

> Resume the debuggee thread only after the future completes and the collected data has been copied out of the JDI objects.

### JDI Cache Warming
```java
public CompletableFuture<Void> warmUpCache(List<Variable> variables) {
    List<CompletableFuture<Void>> futures = new ArrayList<>();

    for (Variable var : variables) {
        if (var.value instanceof ArrayReference) {
            // Pre-fetch array length
            futures.add(CompletableFuture.runAsync(
                () -> ((ArrayReference) var.value).length(),
                debuggerExecutor
            ));
        }

        if (var.value instanceof StringReference) {
            // Pre-fetch string value
            futures.add(CompletableFuture.runAsync(
                () -> ((StringReference) var.value).value(),
                debuggerExecutor
            ));
        }

        if (var.value instanceof ObjectReference) {
            // Pre-fetch type signature
            futures.add(CompletableFuture.runAsync(
                () -> var.value.type().signature(),
                debuggerExecutor
            ));
        }
    }

    return CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0])
    );
}
```

### Performance Tests
```java
@Test
void testLargeObjectInspection() {
    // Create object with 1000 fields
    // Measure variable extraction time
    // Should be <100ms
}

@Test
void testDeepObjectGraph() {
    // Create 10-level deep object graph
    // Measure expansion time at each level
    // Should not timeout
}
```

**Acceptance**: Performance targets met

---

## Task 3.9: Integration Testing

**Time**: 4 hours

### Variable Inspection Test
```java
@Test
void testVariableInspection() {
    // Set breakpoint
    // Hit breakpoint
    // Get stack trace
    // Get variables for top frame
    // Verify locals, this, statics
    // Expand complex object
    // Modify variable
    // Verify new value
}
```

### Large Collection Test
```java
@Test
void testLargeArrayInspection() {
    // Create array with 10000 elements
    // Hit breakpoint
    // Get variables
    // Request pagination (0-100, 100-200, etc.)
    // Verify all elements accessible
}
```

**Acceptance**: All integration tests pass

---

## Phase 3 Completion Checklist

- [ ] All tasks completed
- [ ] Variable extraction works for all types
- [ ] Formatting system complete
- [ ] Variable references work
- [ ] Lazy loading functional
- [ ] Scopes system implemented
- [ ] Variable modification works
- [ ] MCP tool implemented
- [ ] Performance optimizations done
- [ ] Unit tests passing (>80% coverage)
- [ ] Integration tests passing
- [ ] Performance: <100ms for 100 variables
- [ ] Code review completed
- [ ] Documentation updated

---

**End of Phase 3 Task Document**
