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
./scripts/run-with-hotreload.sh

# Debugger-only proxy (when you cannot embed Descartes)
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

### Remote JDWP Target Launch (Agent-Safe)

When a target JVM must be launched by an agent, run it through the managed non-TTY wrapper:

```bash
scripts/launch-managed-nontty.sh \
  --name debug-target \
  -- <target-launch-command>
```

Important:
- Launch without PTY (`tty=false` in tool-based launchers).
- Do not use detached/nohup mode as the default for debugger targets.

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
**Use for:** Diagnosing concurrency issues using progressive disclosure

**Operations:**
- `thread_list` - Lightweight summary of all threads (5-10KB response)
- `thread_inspect` - Detailed view with stack traces for specific threads
- `thread_search` - Combined filter + inspect operation
- `deadlocks` - Detect circular thread dependencies
- `thread_dump` - Full text dump (jstack-style)

**Workflow:**
1. Start with `thread_list` to get overview (no stacks)
2. Filter/sort to find threads of interest
3. Use `thread_inspect` on specific thread IDs for stack traces

**Example - Find blocked threads:**
```json
// Step 1: List blocked threads
{
  "operation": "thread_list",
  "state_filter": ["BLOCKED"],
  "sort_by": "cpu_time"
}

// Step 2: Inspect specific thread
{
  "operation": "thread_inspect",
  "thread_ids": [42],
  "include_stack": true
}
```

**Important:** Never use `include_stack: true` without specifying thread IDs - this causes 200KB+ responses and timeouts. Always use progressive disclosure: list → inspect.

#### 4. Memory Analyzer (`memory_analyzer`)
**Use for:** Memory troubleshooting
- Monitor heap/non-heap usage
- Track memory pool utilization
- Force garbage collection for testing
- Identify potential memory leaks

#### 5. Process Inspector (`process_inspector_stacks`)
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

#### 8. Log File Discovery (`log_file_discovery`)
**Use for:** Automatically discovering log files from Log4j2 configuration
- List all active and rolled log files from configured appenders
- Get file paths, sizes, and timestamps
- Extract timestamp patterns from Log4j2 layouts for guaranteed parsing
- Discover archived/rolled log files matching specific patterns
```json
// List all log files from Log4j2 configuration
{
  "operation": "list"
}

// Get appender configurations
{
  "operation": "appenders"
}

// Discover rolled files for a specific pattern
{
  "operation": "discover",
  "file_pattern": "/var/log/myapp-%d{yyyy-MM-dd}.log.gz"
}
```

**Key capabilities:**
- Zero configuration - works with existing Log4j2 setup
- Automatically extracts timestamp patterns from PatternLayout
- Supports both FileAppender and RollingFileAppender
- Returns timestamp formatter for guaranteed parsing
- Lists both active and archived log files

#### 9. Log File Search (`log_file_search`)
**Use for:** Powerful log file searching and filtering with guaranteed timestamp parsing
- Search logs with regex patterns (grep-like functionality)
- Tail last N lines from log files
- Filter by time ranges using ISO 8601 timestamps or relative times ("1h ago")
- Filter by log level (ERROR, WARN, INFO, DEBUG, TRACE)
- Get specific line ranges
```json
// Search for errors with context
{
  "operation": "grep",
  "file_path": "/var/log/app.log",
  "pattern": "NullPointerException",
  "case_insensitive": true,
  "context_before": 5,
  "context_after": 5,
  "max_results": 100
}

// Tail recent log entries
{
  "operation": "tail",
  "file_path": "/var/log/app.log",
  "lines": 50
}

// Filter by time range (ISO 8601)
{
  "operation": "time_range",
  "file_path": "/var/log/app.log",
  "start_time": "2024-11-09T10:00:00Z",
  "end_time": "2024-11-09T12:00:00Z",
  "level_filter": "ERROR"
}

// Relative time ranges
{
  "operation": "time_range",
  "file_path": "/var/log/app.log",
  "start_time": "1h ago",
  "max_results": 1000
}
```

**Key capabilities:**
- Uses Log4j2 timestamp patterns for guaranteed parsing (no regex guessing)
- Supports multiple time formats via configuration
- Industry standard ISO 8601 timestamps for time filtering
- Relative time syntax: "1h ago", "30m ago", "2d ago"
- Works with both active and rolled/archived log files
- Level filtering (ERROR, WARN, INFO, DEBUG, TRACE)
- Context lines around matches (grep -B/-A style)

**Timestamp parsing:**
The tool automatically uses the timestamp pattern from Log4j2 configuration for accurate parsing:
- No false positives from regex guessing
- Handles any timestamp format defined in log4j2.properties
- Falls back to regex when pattern unavailable
- Timezone-aware parsing

#### 10. Performance Profiler (`profiler_start`, `profiler_hotspots`) 🔥
**Use for:** Production-safe performance analysis with interactive visualization
- Start low-overhead JFR profiling sessions (0.5%-2% overhead)
- Identify CPU, memory allocation, and lock contention bottlenecks
- Generate interactive flame graphs for visual exploration
- Export profiles for offline analysis or archiving

**Starting a profiling session:**
```json
profiler_start: {
  "duration_seconds": 30,
  "profile_type": "cpu",
  "package_filter": "com.myapp"
}
// Returns: profile_id "25-10-2024_14.30.15-profile-abc123"
```

**Profile types:**
- `cpu` - CPU sampling only (~1% overhead) - Best for finding computation hotspots
- `allocation` - Memory allocation tracking - For memory leak investigation
- `comprehensive` - All events: CPU, allocation, locks, I/O, GC (~2% overhead)
- `lightweight` - Low overhead CPU sampling (~0.5%) - Production monitoring

**Analyzing hotspots:**
```json
profiler_hotspots: {
  "profile_id": "25-10-2024_14.30.15-profile-abc123",
  "hotspot_type": "cpu",
  "top_n": 20,
  "min_percentage": 1.0
}
// Returns: Top 20 methods by CPU usage with file:line locations
```

**Understanding call trees:**
```json
profiler_call_tree: {
  "profile_id": "25-10-2024_14.30.15-profile-abc123",
  "method_pattern": "MyService.processData"
}
// Shows: What processData() calls and their time distribution
```

**Generating interactive flame graphs:**
```json
profiler_export: {
  "profile_id": "25-10-2024_14.30.15-profile-abc123",
  "format": "flamegraph"
}
// Returns: Complete HTML with interactive visualization
```

**Flame Graph Visualization:**
The flame graph provides intuitive visual performance analysis:
- **Width** = Time/samples (wider bars = more expensive)
- **Height** = Call stack depth (deeper = more nested calls)
- **Interactive**: Click to zoom into methods, search to highlight patterns
- **Tooltips**: Hover for exact percentages and sample counts
- **Colors**: Automatically colored by package for visual grouping
- **Usage**: Save HTML content to file and open in any browser
- **Navigation**: Use search box to find specific methods, click bars to zoom

**Complete profiling workflow:**
```
1. Start: profiler_start duration=30s, type=cpu
   → Returns profile_id with timestamp

2. Hotspots: profiler_hotspots to find top CPU consumers
   → Example: UserService.processRequest = 45% CPU

3. Drill down: profiler_call_tree on hotspot method
   → Shows: processRequest calls database query (40%) and serialization (5%)

4. Visualize: profiler_export format=flamegraph
   → Generates: Interactive HTML flame graph

5. Analyze: Open HTML in browser
   → Visual exploration with zoom and search
```

**Storage:**
- Profiles stored in: `logs/profiles/` (configurable)
- Format: `.jfr` files (JFR binary) + parsed JSON snapshots
- Retention: Configurable max count with LRU eviction
- Profile IDs include timestamps for easy identification

**Requirements:**
- JDK 11+ (for JFR API)
- Profiler must be enabled in application settings
- Storage space for profile files

### MCP Tool Parameter Passing

When calling Descartes tools through the MCP interface, follow these patterns:

**Strings** - Work as expected:
```python
thread_analyzer(operation="thread_list", sort_by="cpu_time")
```

**Arrays** - Pass as simple values or native arrays, NOT JSON strings:
```python
# ✅ Correct
state_filter="RUNNABLE"
state_filter=["RUNNABLE", "BLOCKED"]

# ❌ Wrong - JSON string literal
state_filter='["RUNNABLE"]'
```

**Numbers** - Pass normally (tools handle string coercion):
```python
max_results=10
max_stack_depth=15
```

**Testing Approach** - Start simple, add parameters incrementally:
1. Test with no parameters: `thread_analyzer(operation="deadlocks")`
2. Add one param: `thread_analyzer(operation="thread_list", sort_by="cpu_time")`
3. Add filters: `thread_analyzer(operation="thread_list", sort_by="cpu_time", state_filter="RUNNABLE")`

**Common Errors:**
- "Parameter required" but you provided it → Check parameter name (e.g., `thread_names` vs `thread_ids`)
- "No enum constant" with array syntax → You passed a JSON string, use native array
- "must be a number, but got String" → Tool should handle this, but try passing number anyway

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
1. Use `log_file_search` to find exception traces in logs
2. Use `object_inspector` to examine objects in the call path
3. Use `jshell_repl` to test the code path with different inputs
4. Verify fix with `jshell_repl` before code changes

#### Analyzing Performance Issues
1. Use `thread_analyzer` to check for blocked threads
2. Use `system_monitoring` for CPU and memory metrics
3. Use `process_inspector_stacks` to see what code is executing
4. Use `memory_analyzer` to check for memory pressure

#### Profiling Performance Bottlenecks
1. Start profiling with `profiler_start` (30-60s, cpu type)
2. Let the application run under normal load
3. Use `profiler_hotspots` to identify top CPU consumers
4. Use `profiler_call_tree` to understand expensive call paths
5. Generate `profiler_export format=flamegraph` for visual analysis
6. Open flame graph in browser, search for known slow operations
7. Identify optimization targets (wide bars in flame graph)
8. Verify improvements with another profiling session

**Example:**
```
// Initial profile
profiler_start: {duration_seconds: 60, profile_type: "cpu"}
// After auto-stop
profiler_hotspots: {profile_id: "...", top_n: 20}
// Finds: DatabaseService.fetchUsers = 55%
profiler_call_tree: {profile_id: "...", method_pattern: "fetchUsers"}
// Shows: N+1 query pattern
profiler_export: {profile_id: "...", format: "flamegraph"}
// Visual confirmation in browser
```

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

// 2. Inspect problematic service
object_inspector: { 
  expression: "context.get('userService')",
  operation: "inspect"
}

// 3. Test fix in JShell
jshell_repl: {
  session_id: "debug-session-1",
  code: "var service = context.get('userService');
         service.clearCache();
         service.processUser(123);"
}
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
