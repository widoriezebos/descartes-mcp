# Descartes MCP Integration - CLAUDE.md Section

Copy this section into your project's CLAUDE.md when integrating Descartes MCP for runtime introspection and debugging.

---

## Descartes MCP Runtime Debugging and Introspection

This project has Descartes MCP integrated, providing powerful runtime introspection and debugging capabilities. Descartes allows you to interact with the running Java application through MCP tools without modifying code or restarting the application.

### When to Use Descartes

**ALWAYS use Descartes MCP tools when:**
- Debugging runtime issues that are hard to reproduce
- Investigating application state without adding debug logs
- Analyzing performance problems in real-time
- Understanding object relationships and runtime behavior
- Troubleshooting memory leaks or thread issues
- Exploring application context and configuration
- Testing fixes or hypotheses without code changes

**Priority: When the application is running with Descartes enabled and you need to debug an issue, ALWAYS check Descartes tools first before suggesting code changes.**

### Checking Descartes Availability

Before using Descartes tools:
1. Check if the MCP server is running: `lsof -i :9080` (or configured port)
2. Look for Descartes startup messages in application logs
3. Check if MCP client configuration includes Descartes server

### Available Descartes Tools

#### 1. JShell REPL (`jshell_repl`)
**Use for:** Interactive Java code execution within the running application
```java
// Examples:
// Inspect application state
var users = context.get("userRepository").findAll();
System.out.println("Active users: " + users.size());

// Test hypotheses
var service = context.get("myService");
var result = service.processData(testInput);

// Modify runtime behavior (use cautiously)
var config = context.get("configuration");
config.setDebugMode(true);
```

**Important:** 
- Session state persists - use `session_id` for continuity
- Access application objects through `context` map
- Changes affect the running application immediately

#### 2. Object Inspector (`object_inspector`)
**Use for:** Deep inspection of objects without code execution
- Examine field values, methods, and type information
- Navigate object graphs safely
- Understand runtime object state
```
Expression: context.get("dataSource")
Operation: inspect
```

#### 3. Thread Analyzer (`thread_analyzer`)
**Use for:** Diagnosing concurrency issues
- Detect deadlocks automatically
- Analyze thread states and lock contention
- Monitor thread CPU usage
- Essential when application hangs or has performance issues

#### 4. Memory Analyzer (`memory_analyzer`)
**Use for:** Memory troubleshooting
- Monitor heap/non-heap usage
- Track memory pool utilization
- Force garbage collection for testing
- Identify potential memory leaks

#### 5. Exception Analysis (`exception_analysis`)
**Use for:** Post-mortem debugging
- Retrieve recent exceptions from log buffer
- Analyze exception patterns and frequencies
- Access full stack traces without log files
```
Operation: get_recent
Count: 10
```

#### 6. Process Inspector (`process_inspector_stacks`)
**Use for:** Stack trace analysis
- Capture thread dumps on demand
- Filter by thread name patterns
- Focus on specific modules or packages
- Understand what code is currently executing

#### 7. System Monitoring (`system_monitoring`)
**Use for:** Real-time performance metrics
- CPU usage and system load
- Memory consumption trends
- Thread pool utilization
- JVM uptime and health

#### 8. Logging Integration (`logging_integration`)
**Use for:** Dynamic log management
- Tail logs in real-time from memory buffer
- Change log levels without restart
- Search logs with regex patterns
- Analyze log frequency and patterns
```
Operation: tail
Lines: 50
Logger: com.myapp.service
```

### Common Debugging Workflows

#### Investigating a NullPointerException
1. Use `exception_analysis` to get the full stack trace
2. Use `object_inspector` to examine objects in the call path
3. Use `jshell_repl` to test the code path with different inputs
4. Verify fix with `jshell_repl` before code changes

#### Analyzing Performance Issues
1. Use `thread_analyzer` to check for blocked threads
2. Use `system_monitoring` for CPU and memory metrics
3. Use `process_inspector_stacks` to see what code is executing
4. Use `memory_analyzer` to check for memory pressure

#### Understanding Application State
1. Use `object_inspector` to explore the context map
2. Use `jshell_repl` to query repositories and services
3. Use resources like `app://context` to list available objects
4. Navigate object relationships interactively

#### Debugging Configuration Issues
1. Access `app://system/properties` resource for system properties
2. Use `jshell_repl` to inspect configuration objects
3. Test configuration changes in real-time
4. Verify environment-specific settings

### Available Resources

Access these through MCP resource requests:

- `app://classpath` - Inspect loaded JARs and dependencies
- `app://system/properties` - View system and environment configuration
- `app://metrics` - Access performance metrics
- `app://threads/dump` - Get formatted thread dumps
- `app://mbeans` - Access JMX MBeans
- `app://context` - Explore application context objects

### Best Practices

1. **Start with Non-Invasive Tools**: Use inspectors and analyzers before JShell
2. **Maintain Session Context**: Use consistent `session_id` for related JShell operations
3. **Document Findings**: Include Descartes discoveries in issue analysis
4. **Test Before Changing**: Verify hypotheses with JShell before modifying code
5. **Monitor Impact**: Use system_monitoring when making runtime changes

### Security Considerations

**WARNING**: JShell provides arbitrary code execution. When using Descartes:
- Only execute code you understand
- Be cautious with state modifications in production
- Never expose Descartes ports to public networks
- Treat JShell access as root-level privilege

### Integration with Development Workflow

When debugging issues in this application:
1. **First**: Check if Descartes is available and running
2. **Second**: Use Descartes tools to understand the runtime state
3. **Third**: Test potential fixes using JShell
4. **Finally**: Implement permanent fixes in code

### Example Debugging Session

```java
// 1. Check application health
system_monitoring: { operation: "health" }

// 2. Look for recent errors
exception_analysis: { operation: "get_recent", count: 5 }

// 3. Inspect problematic service
object_inspector: { 
  expression: "context.get('userService')",
  operation: "inspect"
}

// 4. Test fix in JShell
jshell_repl: {
  session_id: "debug-session-1",
  code: "var service = context.get('userService'); 
         service.clearCache();
         service.processUser(123);"
}

// 5. Verify resolution
exception_analysis: { operation: "get_last" }
```

### Troubleshooting Descartes Connection

If Descartes tools are not available:
1. Verify server is running: `ps aux | grep -i descartes`
2. Check port availability: `lsof -i :9080`
3. Review application startup logs for Descartes initialization
4. Ensure MCP client configuration is correct
5. Test connection with: `telnet localhost 9080`

---

**Remember**: Descartes is your window into the running application. Use it to understand before you change.