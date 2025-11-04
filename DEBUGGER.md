# Descartes MCP Debugger Reference

Comprehensive reference for the JDWP-based debugger tools provided by Descartes MCP.

## Overview

The Descartes debugger provides full interactive debugging capabilities for running Java applications through the Model Context Protocol (MCP). It uses the Java Debug Wire Protocol (JDWP) to attach to your JVM and provides 8 specialized tools for session management, breakpoints, stepping, inspection, and evaluation.

### Key Features

- **Full JDWP debugging**: Attach to any running JVM with debug enabled
- **Breakpoint management**: Set, remove, enable/disable breakpoints with conditions
- **Thread control**: Suspend, resume, and step through code execution
- **Stack inspection**: Capture and analyze stack traces for suspended threads
- **Variable inspection**: Lazy loading of variables with deep object traversal
- **Expression evaluation**: Hybrid evaluator using Janino for simple expressions, JShell for complex ones
- **Watch expressions**: Auto-evaluated expressions when execution suspends
- **Event-driven architecture**: Real-time notifications of breakpoint hits and state changes

### Architecture

The debugger is built on several key components:

**DebuggerService** (`com.bitsapplied.descartes.debugger.DebuggerService`): Core orchestrator managing lifecycle, state machine, and component coordination. Uses single-threaded executor for thread-safe JDI operations.

**State Machine** (`SessionState`): Manages debug session lifecycle with clear state transitions:
```
CREATED → CONNECTING → READY ↔ SUSPENDED ↔ STEPPING
                         ↓            ↓
                    EVALUATING   EVALUATING
                         ↓            ↓
                   DISCONNECTING ← ← ←
                         ↓
                      CLOSED
```

**Key Components**:
- **JDWPConnector**: Attaches to running JVM via socket connection
- **BreakpointManager**: Manages breakpoint lifecycle, conditions, method breakpoints
- **SteppingController**: Handles step over/into/out with configurable skip patterns
- **StackTraceInspector**: Captures and filters stack traces
- **VariableExtractor**: Lazy loading of variables with reference management
- **HybridEvaluationProvider**: Intelligent expression evaluation (Janino → JShell fallback)
- **WatchExpressionManager**: Auto-evaluation of watch expressions on suspend
- **MCPEventBridge**: Broadcasts events to MCP clients via notifications

### Requirements

- **JDK 11+**: Required for JDWP support
- **JDK 17+**: Requires additional `--add-opens` flags for reflection access:
  ```bash
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util=ALL-UNNAMED
  ```
- **Development environment only**: The debugger provides arbitrary code execution capabilities through expression evaluation. Never expose to untrusted networks or users in production.

### Security Warnings

⚠️ **CRITICAL SECURITY CONSIDERATIONS**:

1. **Arbitrary code execution**: Expression evaluation can execute ANY Java code in the target JVM
2. **Development only**: This tool should NEVER be used in production environments
3. **Trusted networks**: Only expose the debugger on localhost or trusted internal networks
4. **No authentication**: The current implementation has no authentication layer
5. **Full JVM access**: Can inspect and modify any object, invoke any method

**DO NOT**:
- ❌ Enable debugging in production JVMs
- ❌ Expose debugger ports to untrusted networks
- ❌ Use in environments with sensitive data without additional security layers
- ❌ Share debugger access with untrusted users

---

## Debugger Tools

The debugger provides 8 specialized tools, each focused on a specific debugging capability. Tools follow a **progressive disclosure pattern**: start with session management, then set breakpoints, wait for suspension, and progressively drill down into stack traces, variables, and expression evaluation.

---

## debugger_session

**Purpose**: Manages debug session lifecycle and provides thread-level control operations.

### Design Philosophy

The session tool is the entry point for all debugging operations. It handles:
1. **Connection management**: Establishes and maintains JDWP connection to target JVM
2. **Lifecycle control**: Start, stop, and monitor debug session state
3. **Thread control**: Suspend, resume individual threads or all threads
4. **Status monitoring**: Query current session state and configuration

All other debugger tools require an active session to function.

### Operations

#### 1. start

Start a new debug session and connect to the target JVM.

**Parameters:**
- `host` (string, optional): Target JVM host (default: "localhost")
- `port` (integer, optional): JDWP port (default: 5005)
- `jdwp_timeout` (integer, optional): Connection timeout in milliseconds (default: 5000)
- `stop_on_entry` (boolean, optional): Suspend all threads on connection (default: false)
- `skip_patterns` (array, optional): Package patterns to skip during stepping
  - Example: `["java.*", "jdk.*", "sun.*"]`
  - Default: `["java.*", "jdk.*", "sun.*", "com.sun.*"]`

**Returns:**
```json
{
  "status": "success",
  "message": "Debug session started successfully",
  "session_id": "debug-session-abc123",
  "state": "READY",
  "target": {
    "host": "localhost",
    "port": 5005,
    "vm_name": "Java HotSpot(TM) 64-Bit Server VM",
    "vm_version": "17.0.1+12-LTS-39"
  }
}
```

**Example:**
```json
{
  "operation": "start",
  "port": 5005,
  "jdwp_timeout": 10000,
  "stop_on_entry": false,
  "skip_patterns": ["java.*", "jdk.*", "org.springframework.*"]
}
```

#### 2. stop

Stop the active debug session and disconnect from the target JVM.

**Parameters:** None

**Returns:**
```json
{
  "status": "success",
  "message": "Debug session stopped successfully",
  "state": "CLOSED"
}
```

**Example:**
```json
{
  "operation": "stop"
}
```

#### 3. status

Get current session status, state, and configuration.

**Parameters:** None

**Returns:**
```json
{
  "status": "success",
  "session_active": true,
  "state": "SUSPENDED",
  "session_id": "debug-session-abc123",
  "target": {
    "host": "localhost",
    "port": 5005,
    "vm_name": "Java HotSpot(TM) 64-Bit Server VM",
    "vm_version": "17.0.1+12-LTS-39"
  },
  "configuration": {
    "skip_patterns": ["java.*", "jdk.*", "sun.*"],
    "stop_on_entry": false
  },
  "suspended_threads": 2,
  "total_threads": 12
}
```

**Example:**
```json
{
  "operation": "status"
}
```

#### 4. threads

List all threads in the debuggee JVM.

**Parameters:**
- `state_filter` (string, optional): Filter by thread state
  - Values: `RUNNING`, `SUSPENDED`, `ALL` (default: "ALL")
- `name_pattern` (string, optional): Regex pattern to filter thread names

**Returns:**
```json
{
  "status": "success",
  "total_threads": 12,
  "filtered_threads": 3,
  "threads": [
    {
      "thread_id": 1,
      "thread_name": "main",
      "state": "SUSPENDED",
      "status": "at breakpoint",
      "frame_count": 5
    },
    {
      "thread_id": 12,
      "thread_name": "worker-1",
      "state": "RUNNING",
      "status": "running",
      "frame_count": 0
    }
  ]
}
```

**Example:**
```json
{
  "operation": "threads",
  "state_filter": "SUSPENDED"
}
```

#### 5. suspend

Suspend a specific thread in the debuggee JVM.

**Parameters:**
- `thread_id` (integer, required): Thread ID to suspend
  - Get thread IDs from `threads` operation

**Returns:**
```json
{
  "status": "success",
  "message": "Thread suspended successfully",
  "thread_id": 1,
  "thread_name": "main",
  "state": "SUSPENDED",
  "frame_count": 5
}
```

**Example:**
```json
{
  "operation": "suspend",
  "thread_id": 1
}
```

#### 6. resume

Resume a specific suspended thread.

**Parameters:**
- `thread_id` (integer, required): Thread ID to resume

**Returns:**
```json
{
  "status": "success",
  "message": "Thread resumed successfully",
  "thread_id": 1,
  "thread_name": "main",
  "state": "RUNNING"
}
```

**Example:**
```json
{
  "operation": "resume",
  "thread_id": 1
}
```

#### 7. resumeAll

Resume all suspended threads in the debuggee JVM.

**Parameters:** None

**Returns:**
```json
{
  "status": "success",
  "message": "All threads resumed successfully",
  "resumed_count": 3,
  "state": "READY"
}
```

**Example:**
```json
{
  "operation": "resumeAll"
}
```

---

## debugger_breakpoints

**Purpose**: Manages breakpoints for halting execution at specific code locations.

### Design Philosophy

Breakpoints are the primary mechanism for controlling when execution suspends. The breakpoint manager supports:
1. **Line breakpoints**: Stop at specific source code lines
2. **Conditional breakpoints**: Only suspend when a condition evaluates to true
3. **Enable/disable**: Toggle breakpoints without removing them
4. **Lifecycle management**: Set, list, remove, enable, disable breakpoints

### Operations

#### 1. set

Set a breakpoint at a specific class and line number.

**Parameters:**
- `class_name` (string, required): Fully qualified class name
  - Example: `"com.myapp.service.UserService"`
- `line_number` (integer, required): Line number in source code
- `condition` (string, optional): Conditional expression (must evaluate to boolean)
  - Example: `"userId > 1000"`
  - Only suspend when condition is true
  - Evaluated in the context of the breakpoint location
- `enabled` (boolean, optional): Whether breakpoint is enabled (default: true)

**Returns:**
```json
{
  "status": "success",
  "message": "Breakpoint set successfully",
  "breakpoint_id": "bp-12345",
  "class_name": "com.myapp.service.UserService",
  "line_number": 42,
  "condition": "userId > 1000",
  "enabled": true,
  "hit_count": 0
}
```

**Example (simple breakpoint):**
```json
{
  "operation": "set",
  "class_name": "com.myapp.service.UserService",
  "line_number": 42
}
```

**Example (conditional breakpoint):**
```json
{
  "operation": "set",
  "class_name": "com.myapp.service.OrderService",
  "line_number": 156,
  "condition": "order.getTotal() > 10000.00"
}
```

#### 2. remove

Remove a specific breakpoint by ID.

**Parameters:**
- `breakpoint_id` (string, required): Breakpoint ID to remove
  - Get IDs from `list` operation

**Returns:**
```json
{
  "status": "success",
  "message": "Breakpoint removed successfully",
  "breakpoint_id": "bp-12345"
}
```

**Example:**
```json
{
  "operation": "remove",
  "breakpoint_id": "bp-12345"
}
```

#### 3. removeAll

Remove all breakpoints.

**Parameters:** None

**Returns:**
```json
{
  "status": "success",
  "message": "All breakpoints removed",
  "removed_count": 5
}
```

**Example:**
```json
{
  "operation": "removeAll"
}
```

#### 4. list

List all active breakpoints.

**Parameters:**
- `include_disabled` (boolean, optional): Include disabled breakpoints (default: true)

**Returns:**
```json
{
  "status": "success",
  "total_breakpoints": 3,
  "breakpoints": [
    {
      "breakpoint_id": "bp-12345",
      "class_name": "com.myapp.service.UserService",
      "line_number": 42,
      "condition": "userId > 1000",
      "enabled": true,
      "hit_count": 15
    },
    {
      "breakpoint_id": "bp-67890",
      "class_name": "com.myapp.controller.ApiController",
      "line_number": 78,
      "condition": null,
      "enabled": false,
      "hit_count": 0
    }
  ]
}
```

**Example:**
```json
{
  "operation": "list"
}
```

#### 5. enable

Enable a previously disabled breakpoint.

**Parameters:**
- `breakpoint_id` (string, required): Breakpoint ID to enable

**Returns:**
```json
{
  "status": "success",
  "message": "Breakpoint enabled successfully",
  "breakpoint_id": "bp-67890",
  "enabled": true
}
```

**Example:**
```json
{
  "operation": "enable",
  "breakpoint_id": "bp-67890"
}
```

#### 6. disable

Disable a breakpoint without removing it.

**Parameters:**
- `breakpoint_id` (string, required): Breakpoint ID to disable

**Returns:**
```json
{
  "status": "success",
  "message": "Breakpoint disabled successfully",
  "breakpoint_id": "bp-12345",
  "enabled": false
}
```

**Example:**
```json
{
  "operation": "disable",
  "breakpoint_id": "bp-12345"
}
```

---

## debugger_threads

**Purpose**: Thread inspection and management with filtering capabilities.

### Design Philosophy

Provides detailed thread information and control, complementing the basic thread operations in `debugger_session`. Focuses on:
1. **Filtered listing**: Find threads by state or name pattern
2. **Detailed inspection**: Get comprehensive thread information
3. **Thread control**: Suspend/resume operations

### Operations

#### 1. list

List threads with optional filtering.

**Parameters:**
- `state_filter` (string, optional): Filter by state
  - Values: `RUNNING`, `SUSPENDED`, `ALL` (default: "ALL")
- `name_pattern` (string, optional): Regex pattern for thread names
- `suspended_only` (boolean, optional): Show only suspended threads (default: false)

**Returns:**
```json
{
  "status": "success",
  "total_threads": 12,
  "filtered_threads": 3,
  "threads": [
    {
      "thread_id": 1,
      "thread_name": "main",
      "state": "SUSPENDED",
      "status": "at breakpoint",
      "location": "com.myapp.Main.main(Main.java:42)",
      "frame_count": 5
    }
  ]
}
```

**Example:**
```json
{
  "operation": "list",
  "name_pattern": "worker-.*",
  "state_filter": "SUSPENDED"
}
```

#### 2. inspect

Get detailed information about a specific thread.

**Parameters:**
- `thread_id` (integer, required): Thread ID to inspect

**Returns:**
```json
{
  "status": "success",
  "thread": {
    "thread_id": 1,
    "thread_name": "main",
    "state": "SUSPENDED",
    "status": "at breakpoint",
    "location": "com.myapp.Main.main(Main.java:42)",
    "frame_count": 5,
    "suspend_count": 1
  }
}
```

**Example:**
```json
{
  "operation": "inspect",
  "thread_id": 1
}
```

#### 3. suspend

Suspend a specific thread.

**Parameters:**
- `thread_id` (integer, required): Thread ID to suspend

**Returns:**
```json
{
  "status": "success",
  "message": "Thread suspended successfully",
  "thread_id": 1,
  "thread_name": "main",
  "state": "SUSPENDED"
}
```

#### 4. resume

Resume a specific thread.

**Parameters:**
- `thread_id` (integer, required): Thread ID to resume

**Returns:**
```json
{
  "status": "success",
  "message": "Thread resumed successfully",
  "thread_id": 1,
  "thread_name": "main",
  "state": "RUNNING"
}
```

#### 5. resumeAll

Resume all suspended threads.

**Parameters:** None

**Returns:**
```json
{
  "status": "success",
  "message": "All threads resumed",
  "resumed_count": 3
}
```

---

## debugger_step

**Purpose**: Controls single-step execution for suspended threads.

### Design Philosophy

Stepping is the core navigation mechanism during debugging. Supports:
1. **Step over**: Execute next line without entering method calls
2. **Step into**: Enter method calls to debug deeper
3. **Step out**: Return to caller method

All stepping operations respect skip patterns configured in the session (e.g., skip JDK classes).

### Operations

**Thread Requirement**: All step operations require the thread to be SUSPENDED.

#### 1. stepOver

Execute the next line in the current method without entering any method calls.

**Parameters:**
- `thread_id` (integer, required): Thread ID to step
  - Thread must be suspended

**Returns:**
```json
{
  "status": "success",
  "message": "Step over completed",
  "thread_id": 1,
  "thread_name": "main",
  "state": "SUSPENDED",
  "location": "com.myapp.Main.main(Main.java:43)"
}
```

**Example:**
```json
{
  "operation": "stepOver",
  "thread_id": 1
}
```

#### 2. stepInto

Step into the next method call.

**Parameters:**
- `thread_id` (integer, required): Thread ID to step
  - Thread must be suspended

**Returns:**
```json
{
  "status": "success",
  "message": "Step into completed",
  "thread_id": 1,
  "thread_name": "main",
  "state": "SUSPENDED",
  "location": "com.myapp.service.UserService.findById(UserService.java:25)"
}
```

**Note**: If the next line calls a method in a skipped package (e.g., `java.*`), stepping will continue until reaching non-skipped code.

**Example:**
```json
{
  "operation": "stepInto",
  "thread_id": 1
}
```

#### 3. stepOut

Step out of the current method and return to the caller.

**Parameters:**
- `thread_id` (integer, required): Thread ID to step
  - Thread must be suspended

**Returns:**
```json
{
  "status": "success",
  "message": "Step out completed",
  "thread_id": 1,
  "thread_name": "main",
  "state": "SUSPENDED",
  "location": "com.myapp.Main.main(Main.java:43)"
}
```

**Example:**
```json
{
  "operation": "stepOut",
  "thread_id": 1
}
```

---

## debugger_stacktrace

**Purpose**: Captures and analyzes stack traces for suspended threads.

### Design Philosophy

Stack traces are essential for understanding execution context. Supports:
1. **Full capture**: Get complete call stack
2. **Filtered capture**: Exclude framework/library frames
3. **Frame access**: Navigate to specific stack frames
4. **Current frame**: Quick access to top-of-stack

**Thread Requirement**: Thread must be SUSPENDED for all operations.

### Operations

#### 1. capture

Capture the full stack trace for a suspended thread.

**Parameters:**
- `thread_id` (integer, required): Thread ID
  - Thread must be suspended
- `max_depth` (integer, optional): Maximum stack depth (default: 100)
  - Limits how many frames are captured

**Returns:**
```json
{
  "status": "success",
  "thread_id": 1,
  "thread_name": "main",
  "frame_count": 5,
  "frames": [
    {
      "frame_index": 0,
      "location": {
        "class_name": "com.myapp.service.UserService",
        "method_name": "findById",
        "signature": "(Ljava/lang/Long;)Lcom/myapp/model/User;",
        "source_file": "UserService.java",
        "line_number": 42
      }
    },
    {
      "frame_index": 1,
      "location": {
        "class_name": "com.myapp.controller.UserController",
        "method_name": "getUser",
        "signature": "(Ljava/lang/Long;)Lorg/springframework/http/ResponseEntity;",
        "source_file": "UserController.java",
        "line_number": 78
      }
    }
  ]
}
```

**Example:**
```json
{
  "operation": "capture",
  "thread_id": 1,
  "max_depth": 50
}
```

#### 2. captureFiltered

Capture stack trace with frame filtering to exclude unwanted packages.

**Parameters:**
- `thread_id` (integer, required): Thread ID (must be suspended)
- `exclude_patterns` (array, optional): Regex patterns to exclude
  - Example: `["java.*", "org.springframework.*", "jdk.*"]`
  - Default: `["java.*", "jdk.*", "sun.*"]`
- `max_depth` (integer, optional): Maximum stack depth (default: 100)

**Returns:**
Same format as `capture`, but with filtered frames.

**Example:**
```json
{
  "operation": "captureFiltered",
  "thread_id": 1,
  "exclude_patterns": ["java.*", "org.springframework.*"],
  "max_depth": 30
}
```

#### 3. getFrame

Get detailed information about a specific stack frame.

**Parameters:**
- `thread_id` (integer, required): Thread ID (must be suspended)
- `frame_index` (integer, required): Frame index (0 = top of stack)

**Returns:**
```json
{
  "status": "success",
  "thread_id": 1,
  "frame_index": 0,
  "location": {
    "class_name": "com.myapp.service.UserService",
    "method_name": "findById",
    "signature": "(Ljava/lang/Long;)Lcom/myapp/model/User;",
    "source_file": "UserService.java",
    "line_number": 42
  },
  "this_object": {
    "type": "com.myapp.service.UserService",
    "object_id": "obj-123"
  }
}
```

**Example:**
```json
{
  "operation": "getFrame",
  "thread_id": 1,
  "frame_index": 0
}
```

#### 4. getCurrentFrame

Get the current (top-most) stack frame.

**Parameters:**
- `thread_id` (integer, required): Thread ID (must be suspended)

**Returns:**
Same format as `getFrame` for frame index 0.

**Example:**
```json
{
  "operation": "getCurrentFrame",
  "thread_id": 1
}
```

---

## debugger_variables

**Purpose**: Inspects variables and object state with lazy loading for efficient memory usage.

### Design Philosophy

Variable inspection uses a **lazy loading pattern** to avoid overwhelming responses:
1. **Initial load**: Get variables for a stack frame (primitive values shown, objects as references)
2. **Expand on demand**: Use `variable_reference` to load child properties
3. **Deep traversal**: Recursively expand objects as needed
4. **Static fields**: Access class-level static fields

This prevents massive responses when inspecting objects with large graphs.

### Operations

#### 1. getVariables

Get all variables (local variables, parameters, `this`) from a specific stack frame.

**Parameters:**
- `thread_id` (integer, required): Thread ID (must be suspended)
- `frame_index` (integer, required): Stack frame index (0 = current)

**Returns:**
```json
{
  "status": "success",
  "thread_id": 1,
  "frame_index": 0,
  "variables": [
    {
      "name": "userId",
      "type": "java.lang.Long",
      "value": "1234",
      "variable_reference": null
    },
    {
      "name": "user",
      "type": "com.myapp.model.User",
      "value": "User@abc123",
      "variable_reference": "var-ref-456"
    },
    {
      "name": "this",
      "type": "com.myapp.service.UserService",
      "value": "UserService@def789",
      "variable_reference": "var-ref-789"
    }
  ]
}
```

**Note**: Objects have a `variable_reference` that can be used with `getChildVariables` to expand.

**Example:**
```json
{
  "operation": "getVariables",
  "thread_id": 1,
  "frame_index": 0
}
```

#### 2. getChildVariables

Expand an object to show its fields/properties.

**Parameters:**
- `variable_reference` (string, required): Variable reference from previous call
  - Obtained from `getVariables` or `getChildVariables`

**Returns:**
```json
{
  "status": "success",
  "variable_reference": "var-ref-456",
  "parent_type": "com.myapp.model.User",
  "children": [
    {
      "name": "id",
      "type": "java.lang.Long",
      "value": "1234",
      "variable_reference": null
    },
    {
      "name": "name",
      "type": "java.lang.String",
      "value": "\"John Doe\"",
      "variable_reference": null
    },
    {
      "name": "orders",
      "type": "java.util.List",
      "value": "ArrayList@xyz",
      "variable_reference": "var-ref-999"
    }
  ]
}
```

**Example:**
```json
{
  "operation": "getChildVariables",
  "variable_reference": "var-ref-456"
}
```

#### 3. getStaticFields

Get static fields of a class.

**Parameters:**
- `class_name` (string, required): Fully qualified class name
  - Example: `"com.myapp.Constants"`

**Returns:**
```json
{
  "status": "success",
  "class_name": "com.myapp.Constants",
  "static_fields": [
    {
      "name": "DEFAULT_TIMEOUT",
      "type": "int",
      "value": "5000",
      "variable_reference": null
    },
    {
      "name": "CONFIG",
      "type": "com.myapp.config.AppConfig",
      "value": "AppConfig@abc",
      "variable_reference": "var-ref-111"
    }
  ]
}
```

**Example:**
```json
{
  "operation": "getStaticFields",
  "class_name": "com.myapp.Constants"
}
```

---

## debugger_evaluate

**Purpose**: Evaluates Java expressions in the context of a suspended thread.

### Design Philosophy

Expression evaluation is powered by a **hybrid evaluator**:
1. **Janino first**: Fast, lightweight compiler for simple expressions
2. **JShell fallback**: Full Java REPL for complex expressions
3. **Context-aware**: Access to local variables, parameters, `this`, and method calls
4. **Caching**: Frequently used expressions are cached for performance

**Security Warning**: Can execute arbitrary code. Development environments only.

### Evaluation Strategy

The hybrid evaluator tries Janino first for speed:
- **Simple expressions**: `userId`, `user.getName()`, `order.getTotal() > 100`
- **Arithmetic**: `price * quantity`, `(a + b) / 2`
- **Comparisons**: `user.isActive() && order.isPaid()`
- **String operations**: `name + " - " + email`

Falls back to JShell for complex cases:
- **Multiple statements**: `int x = 10; int y = 20; x + y`
- **Control flow**: `if (user != null) { return user.getName(); } else { return "Unknown"; }`
- **Imports**: `import java.time.*; LocalDateTime.now()`

### Operations

#### 1. evaluate

Evaluate a Java expression in the context of a suspended thread.

**Parameters:**
- `expression` (string, required): Java expression to evaluate
  - Examples: `userId`, `user.getName()`, `order.getTotal() > 1000`
- `thread_id` (integer, optional*): Thread ID to evaluate in
- `thread_name` (string, optional*): Thread name to evaluate in
- `frame_index` (integer, optional): Stack frame index (default: 0 = current frame)

*One of `thread_id` or `thread_name` is required. Thread must be SUSPENDED.

**Returns (successful evaluation):**
```json
{
  "status": "success",
  "expression": "user.getName()",
  "result": {
    "type": "java.lang.String",
    "value": "\"John Doe\""
  },
  "thread_id": 1,
  "frame_index": 0,
  "evaluator_used": "janino"
}
```

**Returns (evaluation error):**
```json
{
  "status": "error",
  "error_code": "EVALUATION_FAILED",
  "message": "Cannot invoke method 'getName()' on null object",
  "expression": "user.getName()",
  "thread_id": 1,
  "frame_index": 0
}
```

**Example (simple expression):**
```json
{
  "operation": "evaluate",
  "thread_id": 1,
  "frame_index": 0,
  "expression": "userId"
}
```

**Example (method call):**
```json
{
  "operation": "evaluate",
  "thread_name": "main",
  "expression": "user.getName() + \" (\" + user.getEmail() + \")\""
}
```

**Example (complex expression):**
```json
{
  "operation": "evaluate",
  "thread_id": 1,
  "expression": "import java.util.stream.*; orders.stream().mapToDouble(Order::getTotal).sum()"
}
```

---

## debugger_watch

**Purpose**: Manages watch expressions that are automatically evaluated when execution suspends.

### Design Philosophy

Watch expressions provide **persistent monitoring** of values:
1. **Auto-evaluation**: Automatically evaluated when any breakpoint hits
2. **Named watches**: Optional display names for clarity
3. **Enable/disable**: Toggle watches without removing them
4. **Batch evaluation**: Evaluate all watches in one call

Watches are ideal for monitoring state changes across multiple breakpoint hits.

### Operations

#### 1. add

Add a new watch expression.

**Parameters:**
- `expression` (string, required): Java expression to watch
  - Example: `"user.getName()"`, `"order.getTotal()"`, `"count > 100"`
- `display_name` (string, optional): Friendly name for the watch
  - If not provided, expression is used as the name

**Returns:**
```json
{
  "status": "success",
  "message": "Watch expression added",
  "watch_id": "watch-12345",
  "expression": "user.getName()",
  "display_name": "User Name",
  "enabled": true
}
```

**Example:**
```json
{
  "operation": "add",
  "expression": "order.getTotal()",
  "display_name": "Order Total"
}
```

#### 2. remove

Remove a specific watch expression.

**Parameters:**
- `watch_id` (string, required): Watch ID to remove
  - Get IDs from `list` operation

**Returns:**
```json
{
  "status": "success",
  "message": "Watch expression removed",
  "watch_id": "watch-12345"
}
```

**Example:**
```json
{
  "operation": "remove",
  "watch_id": "watch-12345"
}
```

#### 3. removeAll

Remove all watch expressions.

**Parameters:** None

**Returns:**
```json
{
  "status": "success",
  "message": "All watch expressions removed",
  "removed_count": 5
}
```

**Example:**
```json
{
  "operation": "removeAll"
}
```

#### 4. list

List all watch expressions.

**Parameters:**
- `include_disabled` (boolean, optional): Include disabled watches (default: true)

**Returns:**
```json
{
  "status": "success",
  "total_watches": 3,
  "watches": [
    {
      "watch_id": "watch-12345",
      "expression": "user.getName()",
      "display_name": "User Name",
      "enabled": true
    },
    {
      "watch_id": "watch-67890",
      "expression": "order.getTotal()",
      "display_name": "Order Total",
      "enabled": false
    }
  ]
}
```

**Example:**
```json
{
  "operation": "list"
}
```

#### 5. enable

Enable a previously disabled watch expression.

**Parameters:**
- `watch_id` (string, required): Watch ID to enable

**Returns:**
```json
{
  "status": "success",
  "message": "Watch expression enabled",
  "watch_id": "watch-67890",
  "enabled": true
}
```

**Example:**
```json
{
  "operation": "enable",
  "watch_id": "watch-67890"
}
```

#### 6. disable

Disable a watch expression without removing it.

**Parameters:**
- `watch_id` (string, required): Watch ID to disable

**Returns:**
```json
{
  "status": "success",
  "message": "Watch expression disabled",
  "watch_id": "watch-12345",
  "enabled": false
}
```

**Example:**
```json
{
  "operation": "disable",
  "watch_id": "watch-12345"
}
```

#### 7. evaluate

Evaluate all enabled watch expressions in the context of a suspended thread.

**Parameters:**
- `thread_id` (integer, optional*): Thread ID to evaluate in
- `thread_name` (string, optional*): Thread name to evaluate in
- `frame_index` (integer, optional): Stack frame index (default: 0)

*One of `thread_id` or `thread_name` is required if thread is suspended.

**Returns:**
```json
{
  "status": "success",
  "thread_id": 1,
  "frame_index": 0,
  "evaluations": [
    {
      "watch_id": "watch-12345",
      "expression": "user.getName()",
      "display_name": "User Name",
      "result": {
        "type": "java.lang.String",
        "value": "\"John Doe\""
      },
      "status": "success"
    },
    {
      "watch_id": "watch-67890",
      "expression": "order.getTotal()",
      "display_name": "Order Total",
      "result": null,
      "status": "error",
      "error": "Variable 'order' not in scope"
    }
  ]
}
```

**Example:**
```json
{
  "operation": "evaluate",
  "thread_id": 1,
  "frame_index": 0
}
```

---

## Typical Workflows

The debugger tools are designed to work together in common debugging scenarios. Here are the most frequent workflows:

### Workflow 1: Start Session and Set Breakpoint

```javascript
// Step 1: Start debug session
{
  "tool": "debugger_session",
  "operation": "start",
  "port": 5005,
  "skip_patterns": ["java.*", "jdk.*", "org.springframework.*"]
}

// Step 2: Set breakpoint at suspected problem location
{
  "tool": "debugger_breakpoints",
  "operation": "set",
  "class_name": "com.myapp.service.UserService",
  "line_number": 42
}

// Step 3: Check session status
{
  "tool": "debugger_session",
  "operation": "status"
}

// Wait for breakpoint to be hit (client receives event notification)
```

### Workflow 2: Inspect State at Breakpoint

```javascript
// When breakpoint hits, inspect the execution state

// Step 1: Get suspended threads
{
  "tool": "debugger_threads",
  "operation": "list",
  "state_filter": "SUSPENDED"
}

// Step 2: Capture stack trace
{
  "tool": "debugger_stacktrace",
  "operation": "capture",
  "thread_id": 1,
  "max_depth": 20
}

// Step 3: Get variables in current frame
{
  "tool": "debugger_variables",
  "operation": "getVariables",
  "thread_id": 1,
  "frame_index": 0
}

// Step 4: Expand interesting objects
{
  "tool": "debugger_variables",
  "operation": "getChildVariables",
  "variable_reference": "var-ref-456"
}
```

### Workflow 3: Evaluate and Step Through Code

```javascript
// After inspecting state, evaluate expressions and step through

// Step 1: Evaluate condition to understand the bug
{
  "tool": "debugger_evaluate",
  "operation": "evaluate",
  "thread_id": 1,
  "expression": "user.getName() + \" - Active: \" + user.isActive()"
}

// Step 2: Step over to next line
{
  "tool": "debugger_step",
  "operation": "stepOver",
  "thread_id": 1
}

// Step 3: Check new state after stepping
{
  "tool": "debugger_variables",
  "operation": "getVariables",
  "thread_id": 1,
  "frame_index": 0
}

// Step 4: Continue stepping or resume
{
  "tool": "debugger_session",
  "operation": "resume",
  "thread_id": 1
}
```

### Workflow 4: Conditional Breakpoint Investigation

```javascript
// Investigate issue that only occurs under specific conditions

// Step 1: Set conditional breakpoint
{
  "tool": "debugger_breakpoints",
  "operation": "set",
  "class_name": "com.myapp.service.OrderService",
  "line_number": 156,
  "condition": "order.getTotal() > 10000.00 && !order.isPaid()"
}

// Step 2: Add watch expressions to monitor
{
  "tool": "debugger_watch",
  "operation": "add",
  "expression": "order.getTotal()",
  "display_name": "Order Total"
}

{
  "tool": "debugger_watch",
  "operation": "add",
  "expression": "order.isPaid()",
  "display_name": "Order Paid Status"
}

// When breakpoint hits (condition met):

// Step 3: Evaluate all watches
{
  "tool": "debugger_watch",
  "operation": "evaluate",
  "thread_id": 1
}

// Step 4: Inspect stack and variables
{
  "tool": "debugger_stacktrace",
  "operation": "captureFiltered",
  "thread_id": 1,
  "exclude_patterns": ["java.*", "org.springframework.*"]
}
```

### Workflow 5: Multi-Breakpoint Debugging

```javascript
// Set multiple breakpoints to trace execution flow

// Step 1: Set breakpoints at key locations
{
  "tool": "debugger_breakpoints",
  "operation": "set",
  "class_name": "com.myapp.controller.OrderController",
  "line_number": 45
}

{
  "tool": "debugger_breakpoints",
  "operation": "set",
  "class_name": "com.myapp.service.OrderService",
  "line_number": 78
}

{
  "tool": "debugger_breakpoints",
  "operation": "set",
  "class_name": "com.myapp.repository.OrderRepository",
  "line_number": 123
}

// Step 2: List all breakpoints to verify
{
  "tool": "debugger_breakpoints",
  "operation": "list"
}

// Step 3: At each breakpoint, check state and continue
{
  "tool": "debugger_evaluate",
  "operation": "evaluate",
  "thread_id": 1,
  "expression": "orderDto.toString()"
}

{
  "tool": "debugger_session",
  "operation": "resume",
  "thread_id": 1
}

// Repeat for each breakpoint hit
```

### Workflow 6: Session Cleanup

```javascript
// Clean up at end of debugging session

// Step 1: Resume all suspended threads
{
  "tool": "debugger_session",
  "operation": "resumeAll"
}

// Step 2: Remove all breakpoints
{
  "tool": "debugger_breakpoints",
  "operation": "removeAll"
}

// Step 3: Remove all watches
{
  "tool": "debugger_watch",
  "operation": "removeAll"
}

// Step 4: Stop debug session
{
  "tool": "debugger_session",
  "operation": "stop"
}
```

---

## Performance Characteristics

| Tool | Operation | Typical Time | Notes |
|------|-----------|--------------|-------|
| debugger_session | start | 100-500ms | JDWP connection overhead |
| debugger_session | stop | 50-200ms | Cleanup and disconnection |
| debugger_session | status | 10-50ms | Lightweight status check |
| debugger_breakpoints | set | 50-200ms | Class loading may add latency |
| debugger_breakpoints | list | 10-30ms | Lightweight |
| debugger_threads | list | 20-100ms | Depends on thread count |
| debugger_step | stepOver/Into/Out | 100-500ms | Depends on skip patterns |
| debugger_stacktrace | capture | 50-200ms | Depends on stack depth |
| debugger_variables | getVariables | 50-150ms | Frame inspection |
| debugger_variables | getChildVariables | 30-100ms | Object traversal |
| debugger_evaluate | evaluate (simple) | 50-200ms | Janino compiler |
| debugger_evaluate | evaluate (complex) | 200-1000ms | JShell fallback |
| debugger_watch | evaluate | 100-500ms | Multiple evaluations |

**Performance Tips:**
- Use `captureFiltered` instead of `capture` to reduce stack trace size
- Limit `max_depth` for stack traces to 20-30 frames for faster responses
- Simple expressions evaluate much faster than complex ones
- Variable expansion is lazy - only expand what you need

---

## Best Practices

### Starting a Debug Session

**DO:**
- ✅ Configure skip patterns to avoid stepping through framework code
- ✅ Check session status before performing operations
- ✅ Use reasonable JDWP timeout (5000-10000ms)
- ✅ Set `stop_on_entry: false` unless you need immediate suspension

**DON'T:**
- ❌ Start multiple debug sessions to the same JVM
- ❌ Use extremely short timeouts (<1000ms)
- ❌ Skip application packages in skip patterns

### Setting Breakpoints

**DO:**
- ✅ Use conditional breakpoints for rare conditions
- ✅ Disable breakpoints when not needed instead of removing
- ✅ List breakpoints periodically to verify active breakpoints
- ✅ Use specific class names (fully qualified)

**DON'T:**
- ❌ Set breakpoints in hot loops without conditions
- ❌ Use complex expressions in conditions (slows execution)
- ❌ Set breakpoints in JDK classes (rarely useful)

### Inspecting Variables

**DO:**
- ✅ Use lazy loading - expand objects on demand
- ✅ Start with getVariables for frame overview
- ✅ Use getChildVariables for deep inspection
- ✅ Check variable_reference before expanding

**DON'T:**
- ❌ Expand all objects immediately (massive responses)
- ❌ Expand circular references without limit
- ❌ Inspect variables when thread is not suspended

### Evaluating Expressions

**DO:**
- ✅ Start with simple expressions
- ✅ Test expressions in isolation before complex combinations
- ✅ Use watches for expressions you evaluate frequently
- ✅ Check thread state before evaluation

**DON'T:**
- ❌ Evaluate expressions with side effects (modifying state)
- ❌ Use infinite loops or blocking operations
- ❌ Evaluate expensive operations (large I/O, DB queries) repeatedly
- ❌ Trust user input in expressions (code injection risk)

### Stepping Through Code

**DO:**
- ✅ Configure skip patterns appropriately
- ✅ Use stepOver for most navigation
- ✅ Use stepInto when you need to debug method internals
- ✅ Use stepOut when deep in call stack

**DON'T:**
- ❌ Step without checking current location
- ❌ Step through thousands of iterations manually
- ❌ Clear skip patterns unless necessary

### Session Management

**DO:**
- ✅ Always stop session when done
- ✅ Resume threads before stopping session
- ✅ Remove breakpoints when no longer needed
- ✅ Clear watches at end of session

**DON'T:**
- ❌ Leave sessions running indefinitely
- ❌ Stop session with threads suspended (may hang debuggee)
- ❌ Accumulate hundreds of breakpoints

---

## Security and Safety

### Development Only

The debugger is designed for **development environments ONLY**:

**Why?**
1. **Code execution**: Expression evaluation can run arbitrary Java code
2. **Performance impact**: Breakpoints and stepping slow execution significantly
3. **State inspection**: Can access sensitive data (passwords, tokens, PII)
4. **No authentication**: Current implementation has no auth layer
5. **Debugging overhead**: JDWP connection consumes memory and CPU

### Safe Usage

**Development Environments:**
- ✅ Local development (localhost connections)
- ✅ Development servers on isolated networks
- ✅ Staging environments with access controls
- ✅ Testing environments

**NEVER Use In:**
- ❌ Production environments
- ❌ Customer-facing systems
- ❌ Systems with PII or sensitive data (without additional security)
- ❌ Untrusted networks

### Expression Evaluation Security

Expression evaluation can execute **any Java code**:

```java
// Safe examples
"userId"
"user.getName()"
"order.getTotal() > 1000"

// DANGEROUS examples (DO NOT USE)
"Runtime.getRuntime().exec(\"rm -rf /\")"  // System command execution
"System.exit(1)"  // Crash the JVM
"FileWriter fw = new FileWriter(\"/etc/passwd\"); fw.write(\"hacked\");"  // File system access
```

**Mitigation:**
1. **Trusted users only**: Only allow trusted developers to use debugger tools
2. **Code review**: Review complex expressions before execution
3. **Sandboxing**: Run debuggee in containers/VMs with limited permissions
4. **Monitoring**: Log all evaluated expressions for audit trail
5. **Network isolation**: Only expose debugger on localhost or VPN

---

## Troubleshooting

### Connection Issues

**Problem**: `debugger_session start` fails with timeout

**Solutions:**
1. Verify target JVM is running with JDWP enabled:
   ```bash
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
   ```
2. Check port is not blocked by firewall
3. Increase `jdwp_timeout` parameter
4. Verify host and port are correct

**Problem**: "Connection refused"

**Solutions:**
1. Check if target JVM is listening on correct port:
   ```bash
   lsof -i :5005
   ```
2. Verify JDWP is configured with `address=*:5005` (not `address=localhost:5005`)
3. Check network connectivity

### Breakpoint Issues

**Problem**: Breakpoint not hit

**Solutions:**
1. Verify class name is fully qualified: `com.myapp.service.UserService`
2. Check line number has executable code (not comments or blank lines)
3. Confirm class is loaded: check thread stack traces
4. Verify breakpoint is enabled: use `debugger_breakpoints list`

**Problem**: Conditional breakpoint always/never suspends

**Solutions:**
1. Test condition with `debugger_evaluate` first
2. Verify condition evaluates to boolean
3. Check variable names in scope
4. Simplify condition for debugging

### Thread State Issues

**Problem**: "Thread not suspended" error

**Solutions:**
1. Check thread state: `debugger_threads list`
2. Suspend thread: `debugger_session suspend` with thread_id
3. Wait for breakpoint hit
4. Check if thread was resumed accidentally

**Problem**: Thread stuck in SUSPENDED state

**Solutions:**
1. Resume thread: `debugger_session resume` with thread_id
2. Resume all: `debugger_session resumeAll`
3. Stop and restart session if unrecoverable

### Evaluation Issues

**Problem**: Expression evaluation fails

**Solutions:**
1. Verify thread is suspended
2. Check variable is in scope for current frame
3. Test with simpler expression first
4. Check for null values
5. Use `debugger_variables getVariables` to see available variables

**Problem**: "Cannot find symbol" error

**Solutions:**
1. Verify variable name spelling
2. Check if variable is in scope for current frame
3. Use `this.fieldName` for instance fields
4. Import required classes for complex expressions

### JDK 17+ Issues

**Problem**: Reflection errors with JDK 17+

**Solutions:**
Add required `--add-opens` flags to target JVM:
```bash
java --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar app.jar
```

### Performance Issues

**Problem**: Stepping is very slow

**Solutions:**
1. Verify skip patterns are configured
2. Add framework packages to skip patterns
3. Use stepOver instead of stepInto when possible
4. Check if stepping through hot loop (use conditional breakpoint instead)

**Problem**: Evaluation takes too long

**Solutions:**
1. Simplify expression
2. Avoid expensive operations (I/O, DB queries)
3. Check if expression has infinite loop
4. Use simpler expressions that trigger Janino instead of JShell

---

## Error Codes

The debugger uses structured error codes for clear error handling:

| Error Code | Description | Common Causes |
|-----------|-------------|---------------|
| `INVALID_PARAMETERS` | Invalid or missing parameters | Missing required params, wrong types |
| `INVALID_OPERATION` | Operation not supported | Typo in operation name |
| `SESSION_INVALID_STATE` | Session in wrong state for operation | Operation requires READY or SUSPENDED state |
| `THREAD_NOT_FOUND` | Thread ID does not exist | Thread exited, wrong ID |
| `THREAD_NOT_SUSPENDED` | Thread must be suspended | Forgot to suspend, thread resumed |
| `INVALID_FRAME` | Frame index out of bounds | Frame_index >= stack depth |
| `EVALUATION_FAILED` | Expression evaluation error | Syntax error, null reference, wrong type |
| `BREAKPOINT_NOT_FOUND` | Breakpoint ID does not exist | Wrong ID, already removed |
| `INTERNAL_ERROR` | Unexpected internal error | JDI exception, connection lost |

---

## Summary

The Descartes debugger provides comprehensive JDWP-based debugging through 8 specialized MCP tools:

1. **debugger_session** - Session lifecycle and thread control
2. **debugger_breakpoints** - Breakpoint management with conditions
3. **debugger_threads** - Thread inspection and filtering
4. **debugger_step** - Step over/into/out operations
5. **debugger_stacktrace** - Stack trace capture and filtering
6. **debugger_variables** - Lazy variable inspection
7. **debugger_evaluate** - Hybrid expression evaluation
8. **debugger_watch** - Auto-evaluated watch expressions

**Key Principles:**
- Progressive disclosure: Start with session → breakpoints → inspect → evaluate
- Lazy loading: Variables and objects loaded on demand
- Security first: Development environments only, code execution risks
- Performance conscious: Skip patterns, filtered stack traces, caching
- Event-driven: Real-time notifications of state changes

**Getting Started:**
1. Enable JDWP on target JVM: `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`
2. Start session: `debugger_session start` with port
3. Set breakpoints: `debugger_breakpoints set` at key locations
4. Wait for breakpoint hit (event notification)
5. Inspect state: stack traces, variables, evaluation
6. Step through code or resume execution
7. Clean up: resume threads, remove breakpoints, stop session

For detailed examples and integration guidance, see [CLAUDE.md](CLAUDE.md).
