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

### Starting Descartes with Hot Reload Support

For hot reload capability, start the application with the Java agent:
```bash
# Basic: Start with hot reload support
java -javaagent:descartes-mcp-jar-with-dependencies.jar \
     -jar descartes-mcp-jar-with-dependencies.jar

# With additional JVM flags for better compatibility
java -XX:+EnableDynamicAgentLoading \
     -javaagent:descartes-mcp-jar-with-dependencies.jar \
     -jar your-application.jar

# Using the convenience script
./run-with-hotreload.sh
```

### Available Descartes Tools

#### 1. Hot Class Reload (`hot_reload_classes`) 🔥 **[Requires Agent Mode]**
**Use for:** Dynamically reloading modified Java classes without restarting
```json
// Reload all modified classes in a package
{
  "packageFilter": "com.myapp.service.*",
  "force": false,
  "validateOnly": false
}

// Validate changes without reloading
{
  "packageFilter": "com.myapp.*",
  "validateOnly": true
}

// Force reload even without file changes
{
  "packageFilter": "com.myapp.utils.Helper",
  "force": true
}
```

**Key capabilities:**
- Reload method implementations instantly
- Update logging and debug statements
- Fix bugs without restart
- Test changes immediately
- Validate compatibility before reload

**Limitations:**
- Cannot add/remove fields
- Cannot change method signatures
- Cannot modify class hierarchy
- Cannot change interfaces

**Workflow:**
1. Edit your Java source files
2. Compile: `mvn compile` or `gradle classes`
3. Use hot_reload_classes tool to reload
4. Test changes with JShell

#### 2. JShell REPL (`jshell_repl`)
**Use for:** Interactive Java code execution within the running application
```java
// Examples:
// Inspect application state
var users = context.get("userRepository").findAll();
System.out.println("Active users: " + users.size());

// Test hypotheses
var service = context.get("myService");
var result = service.processData(testInput);

// Test hot-reloaded classes
var reloadedClass = Class.forName("com.myapp.service.UpdatedService");
var instance = context.get("updatedService");
instance.newMethod(); // Test the reloaded method

// Modify runtime behavior (use cautiously)
var config = context.get("configuration");
config.setDebugMode(true);
```

**Important:** 
- Session state persists - use `session_id` for continuity
- Access application objects through `context` map
- Changes affect the running application immediately
- Perfect for testing hot-reloaded classes

#### 3. Object Inspector (`object_inspector`)
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

#### Hot Reload Development Cycle
1. Identify the class/method that needs changes
2. Edit the Java source file with your fix
3. Compile the changes: `mvn compile` or `gradle classes`
4. Use `hot_reload_classes` to reload: `{"packageFilter": "com.myapp.ClassName"}`
5. Test with `jshell_repl` to verify the fix works
6. Iterate without restarting!

**Example session:**
```json
// 1. Check current behavior with JShell
jshell_repl: {
  "session_id": "fix-session",
  "code": "var calc = context.get('calculator'); calc.calculate(10, 0);"
}
// Returns error: Division by zero

// 2. Fix the code in Calculator.java, add zero check
// 3. Compile: mvn compile

// 4. Hot reload the fixed class
hot_reload_classes: {
  "packageFilter": "com.myapp.util.Calculator",
  "validateOnly": false
}
// Returns: Successfully reloaded 1 class

// 5. Test the fix
jshell_repl: {
  "session_id": "fix-session", 
  "code": "calc.calculate(10, 0);"
}
// Returns: 0 (or appropriate default)
```

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
6. **Hot Reload First**: When agent is loaded, try hot reload before restarting
7. **Validate Before Reload**: Use `validateOnly: true` to check compatibility
8. **Incremental Changes**: Reload small sets of classes for easier debugging

### Security Considerations

**WARNING**: JShell and Hot Reload provide powerful runtime capabilities. When using Descartes:
- Only execute code you understand
- Be cautious with state modifications in production
- Never expose Descartes ports to public networks
- Treat JShell access as root-level privilege
- Hot reload requires Java agent with full JVM access
- Validate all changes before reloading in production
- Monitor application behavior after hot reloads

### Integration with Development Workflow

When debugging issues in this application:
1. **First**: Check if Descartes is available and running
2. **Second**: Check if agent is loaded for hot reload: `jshell_repl: {"code": "com.bitsapplied.descartes.hotreload.agent.HotReloadAgent.isAgentLoaded()"}`
3. **Third**: Use Descartes tools to understand the runtime state
4. **Fourth**: Fix and hot reload if possible, or test with JShell
5. **Finally**: Implement permanent fixes in code

#### Quick Hot Reload Check
```java
// In JShell - Check if hot reload is available
jshell_repl: {
  "session_id": "check",
  "code": "var agent = com.bitsapplied.descartes.hotreload.agent.HotReloadAgent.isAgentLoaded(); 
          System.out.println(\"Hot reload available: \" + agent);"
}
```

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

If hot reload is not working:
1. Check agent was loaded at startup: Look for `-javaagent` in process args
2. Verify compilation output directory matches runtime classpath
3. Check for SecurityManager restrictions
4. Review agent initialization logs at startup
5. Test with a simple class first before complex ones
6. Ensure you're not trying incompatible changes (field/signature changes)

---

## Hot Reload Quick Reference

### When to Use Hot Reload

**Perfect for:**
- Fixing bugs in method implementations
- Adding/updating logging statements
- Adjusting business logic
- Tweaking algorithms
- Updating error messages
- Modifying return values
- Changing conditional logic

**Cannot be used for:**
- Adding/removing fields
- Changing method signatures
- Modifying class hierarchy
- Adding/removing methods
- Changing annotations
- Interface modifications

### Hot Reload Command Examples

```json
// Basic reload of a single class
hot_reload_classes: {
  "packageFilter": "com.myapp.service.UserService"
}

// Reload all classes in a package
hot_reload_classes: {
  "packageFilter": "com.myapp.service.*"
}

// Validate changes first (recommended)
hot_reload_classes: {
  "packageFilter": "com.myapp.*",
  "validateOnly": true
}

// Force reload even without timestamp changes
hot_reload_classes: {
  "packageFilter": "com.myapp.util.*",
  "force": true
}
```

### Complete Hot Reload Workflow

```bash
# 1. Start application with agent
java -XX:+EnableDynamicAgentLoading \
     -javaagent:descartes-mcp-jar-with-dependencies.jar \
     -jar your-app.jar

# 2. Edit your Java files
vim src/main/java/com/myapp/service/BrokenService.java

# 3. Compile changes
mvn compile -pl :your-module

# 4. Connect to Descartes and reload
# Use your MCP client to send:
{
  "tool": "hot_reload_classes",
  "arguments": {
    "packageFilter": "com.myapp.service.BrokenService",
    "validateOnly": false
  }
}

# 5. Test the fix immediately with JShell
{
  "tool": "jshell_repl",
  "arguments": {
    "session_id": "test-fix",
    "code": "var service = context.get('brokenService'); service.fixedMethod();"
  }
}
```

### Response Format

Success response:
```json
{
  "status": "success",
  "classesAnalyzed": 5,
  "classesChanged": 2,
  "classesReloaded": 2,
  "reloadedClasses": [
    "com.myapp.service.UserService",
    "com.myapp.service.OrderService"
  ],
  "reloadTimeMs": 45,
  "message": "Successfully reloaded 2 classes"
}
```

Validation response:
```json
{
  "status": "success",
  "classesAnalyzed": 10,
  "classesChanged": 3,
  "classesReloaded": 0,
  "incompatibleChanges": {
    "com.myapp.model.User": "Field added: private String newField"
  },
  "message": "Validation complete: 3 compatible changes, 1 incompatible"
}
```

Error response:
```json
{
  "status": "error",
  "error": "Hot reload agent not loaded. Start with -javaagent flag",
  "agentRequired": true,
  "suggestion": "Restart with: java -javaagent:descartes.jar -jar app.jar"
}
```

### Pro Tips

1. **Always validate first** when reloading multiple classes
2. **Use specific class names** for faster reload of single files
3. **Compile incrementally** with `mvn compile -pl :module` for large projects
4. **Keep a JShell session** open for testing reloaded classes
5. **Monitor logs** during reload for any initialization issues
6. **Start with small changes** and reload frequently
7. **Use force reload** sparingly, only when timestamp detection fails

---

**Remember**: Descartes is your window into the running application. Use it to understand before you change. With hot reload, it's also your tool to fix without restarting.