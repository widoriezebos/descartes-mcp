# Descartes MCP

A Java-based Model Context Protocol (MCP) server that provides deep introspection, monitoring, debugging, and REPL capabilities for Java applications. Descartes enables AI assistants to interact with running Java processes through a comprehensive set of tools and resources.

> ⚠️ **CRITICAL SECURITY WARNING**:
>
> This tool provides **ARBITRARY CODE EXECUTION** capabilities through multiple features:
> - **JShell REPL**: Execute any Java code in the target JVM
> - **Debugger Tools** (NEW): Full debugging control including expression evaluation
> - **JDWP Access**: Complete JVM control (read/modify memory, change execution flow)
>
> **NEVER use in production or expose to untrusted networks/users.**
>
> **Safe Usage**: Local development only, isolated containers, secure CI/CD, developer workstations
>
> See [Security Considerations](#security-considerations) for complete details.

## Requirements

### Minimum JDK Version: **Java 11+**

Descartes requires **JDK 11 or higher** for the following features:
- **Profiler**: JFR (Java Flight Recorder) API support (JDK 11+)
- **Debugger**: JDWP self-attach and JDI (Java Debug Interface) (JDK 11+)
- **Modern Java Features**: Records, text blocks, enhanced switch expressions

### JDK Version Compatibility Matrix

| JDK Version | Debugger Support | Required JVM Flags | Notes |
|-------------|------------------|-------------------|-------|
| **JDK 8-10** | ❌ Not Supported | N/A | Self-attach unreliable, missing JFR |
| **JDK 11-16** | ✅ Supported | `-Djdk.attach.allowAttachSelf=true`<br/>`--add-modules jdk.attach,jdk.jdi` | Minimum version, stable |
| **JDK 17-20** | ✅ Supported | `-Djdk.attach.allowAttachSelf=true`<br/>`--add-modules jdk.attach,jdk.jdi`<br/>`--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED` | **CRITICAL**: `--add-opens` required for JPMS |
| **JDK 21-22** | ✅ Supported | Same as JDK 17+ | LTS (21), full virtual thread support |
| **JDK 23+** | ✅ Fully Supported | Same as JDK 17+ | Project target version |

### Running with Debugger Support

**JDK 11-16**:
```bash
mvn exec:java -Djdk.attach.allowAttachSelf=true
```

**JDK 17+ (CRITICAL - Missing `--add-opens` will cause failure)**:
```bash
mvn exec:java \
  -Djdk.attach.allowAttachSelf=true \
  -Dexec.args="--add-modules jdk.attach,jdk.jdi --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED"
```

**With Agent (Hot Reload + Debugger) on JDK 17+**:
```bash
mvn compile exec:exec -Prun-with-agent \
  -Djdk.attach.allowAttachSelf=true \
  -Dexec.args="--add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED"
```

**Why JDK 17+ Needs `--add-opens`**: JDK 17 introduced stronger JPMS (Java Platform Module System) encapsulation. The Attach API uses reflection to access `sun.tools.attach` package-private classes. Without `--add-opens`, you'll get `InaccessibleObjectException` at runtime.

### Other Requirements
- Maven 3.6+
- Node.js (for the MCP TCP adapter)

## Protocol Compatibility

### Supported MCP Protocol Versions
Descartes implements **MCP Protocol Version `2024-11-05`** (latest).

### Compatibility Policy
- **Backward compatibility**: We maintain compatibility with the current protocol version
- **Forward compatibility**: Clients should gracefully handle unknown response fields following MCP best practices
- **Breaking changes**: Will be announced with clear migration paths when the MCP protocol evolves

### Version Information
The server advertises its protocol version in the `initialize` response. Clients should verify compatibility during the handshake phase and fail gracefully if the version is not supported.

Example initialize response:
```json
{
  "protocolVersion": "2024-11-05",
  "capabilities": { ... },
  "serverInfo": { ... }
}
```

## Features

### 🛠️ Tools
- **JShell REPL**: Interactive Java code execution with session management
- **Object Inspector**: Deep object introspection without code execution
- **Hot Class Reload**: Dynamically reload Java classes at runtime without restart
- **Process Inspector**: Process and thread information monitoring
- **System Monitoring**: Real-time system metrics and resource usage
- **Thread Analyzer**: Thread state analysis and deadlock detection
- **Memory Analyzer**: Heap usage, garbage collection, and memory pool analysis
- **Exception Analysis**: Stack trace analysis and root cause identification
- **Logging Integration**: Log4j2 integration for log capture and analysis
- **Performance Profiler**: JFR-based profiling with interactive HTML flame graphs (6 tools)
  - Start/stop profiling sessions with configurable overhead (0.5%-2%)
  - Analyze CPU, memory allocation, and lock contention hotspots
  - Generate call trees to understand method hierarchies
  - Export interactive flame graphs with zoom, search, and tooltips
  - List and manage stored profiles
  - Requires JDK 11+ for JFR support

### 📊 Resources
- **Classpath Resource**: Access to classpath information
- **System Properties**: JVM and system property access
- **Metrics Resource**: Application and JVM metrics
- **Thread Dumps**: Detailed thread state snapshots
- **MBean Resource**: JMX MBean access and monitoring
- **Application Context**: Access to registered application objects

## Requirements

- Java 16 or higher (compiled with Java 23 for optimal performance)
- Maven 3.6+
- Node.js (for the MCP TCP adapter)

## Quick Start

### 1. Clone and Build

```bash
gh repo clone widoriezebos/descartes-mcp
cd descartes-mcp
mvn clean package
```

### 2. Run the Example Server

```bash
# Standard mode (no hot reload)
mvn exec:java

# With hot reload support - EASIEST WAY
mvn compile exec:exec -Prun-with-agent

# Or manually with hot reload support
java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar \
     -jar target/descartes-mcp-*-jar-with-dependencies.jar

# Or use the convenient script for hot reload
./run-with-hotreload.sh
```

This starts the MCP server on port 9080 with all available tools and resources registered. When run with the `-javaagent` flag, hot class reload capability is enabled, allowing you to modify and reload classes at runtime.

### 3. Connect with an MCP Client

The repository includes a robust TCP adapter client in `/config/mcp/` for easy integration:

#### Using the Included TCP Adapter

1. **Make the adapter executable** (if needed):
   - **Linux/macOS**: `chmod +x config/mcp/mcp-tcp-adapter.js`
   - **Windows**: No action needed - Node.js handles execution
   - **Alternative**: Run directly with Node.js: `node config/mcp/mcp-tcp-adapter.js`

2. **Update the configuration file** (`config/mcp/mcpservers.json`):
   ```json
   "args": ["/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"]
   ```

3. **Copy to Claude Desktop configuration**:
   - Copy `mcpservers.json` to your Claude Desktop config directory
   - The adapter will handle all connection management automatically

4. **Features of the TCP Adapter**:
   - Automatic reconnection with exponential backoff
   - Message queuing during disconnections
   - Health monitoring and stale connection recovery
   - Never exits - handles all connection failures gracefully

See `/config/mcp/README-adapter.md` for detailed adapter documentation.

## Build Commands

```bash
# Build the project
mvn clean compile

# Run tests (excludes concurrency tests by default)
mvn test

# Run concurrency tests only
mvn test -Pconcurrency-tests

# Run all tests including concurrency tests
mvn test -Pall-tests

# Package with dependencies
mvn clean package

# Run the example server
mvn exec:java
```

## Documentation

- **[TOOLS.md](TOOLS.md)** - Comprehensive tool reference with all operations and parameters
- **[HOT_RELOAD_GUIDE.md](HOT_RELOAD_GUIDE.md)** - Comprehensive guide for using the hot class reload feature
- **[CLAUDE.md](CLAUDE.md)** - Codebase guidance for AI assistants (Claude Code)
- **[CLAUDE-SECTION.md](CLAUDE-SECTION.md)** - Template for integrating Descartes docs into your project
- **[/config/mcp/README-adapter.md](/config/mcp/README-adapter.md)** - TCP adapter documentation

## Integration Guide

### Context Injection Requirements

**IMPORTANT**: Some tools require access to the application context to function properly. Without context injection, these tools cannot access application state:

#### Tools Requiring Context Injection:
- **JShellTool** - Requires context to access application objects in the REPL environment
- **JShellSessionTool** - Requires context for session management with application state access
- **ObjectInspectorTool** - Requires context to inspect application objects
- **HotClassReloadTool** - Requires context for accessing application class information

These tools MUST be instantiated with a `Map<String, Object> context` parameter:
```java
Map<String, Object> context = new HashMap<>();
context.put("myService", myService);
context.put("repository", repository);

// Tools that REQUIRE context
server.registerTool(new JShellTool(context));
server.registerTool(new JShellSessionTool(context));
server.registerTool(new ObjectInspectorTool(context));
```

Without the context parameter, the REPL and object inspection tools will not have access to your application state, severely limiting their debugging capabilities.

#### Tools That Don't Require Context:
The following tools work independently and don't need context injection:
- **ProcessInspectorTool** - Inspects JVM process information
- **SystemMonitoringTool** - Monitors system resources
- **ThreadAnalyzerTool** - Analyzes thread states
- **MemoryAnalyzerTool** - Analyzes memory usage
- **ExceptionAnalysisTool** - Analyzes exceptions from logs
- **LoggingIntegrationTool** - Manages logging configuration

### Maven Configuration Checklist

When embedding Descartes into **your own application**, mirror the Maven setup from this repository so debugger features and hot reload work on modern JDKs:

1. **Surefire `argLine` flags** – copy the module/agent flags we use. They are required for Attach/JDI access under JPMS and for loading the hot-reload agent on JDK 21+.

   ```xml
   <plugin>
     <groupId>org.apache.maven.plugins</groupId>
     <artifactId>maven-surefire-plugin</artifactId>
     <version>3.5.2</version>
     <configuration>
       <argLine>
         -Xshare:off
         -XX:+UnlockDiagnosticVMOptions
         -XX:+EnableDynamicAgentLoading
         --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED
         --add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED
         --add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED
         -Xlog:jfr=warning:stdout
       </argLine>
       <reuseForks>false</reuseForks>
       <redirectTestOutputToFile>true</redirectTestOutputToFile>
     </configuration>
   </plugin>
   ```

   - `-Xshare:off` prevents class-data sharing from blocking JDWP/attach in forked tests.
   - `-XX:+UnlockDiagnosticVMOptions` must precede `EnableDynamicAgentLoading`.
   - `-XX:+EnableDynamicAgentLoading` allows the hot-reload agent to load dynamically.
   - The three `--add-opens` lines expose Attach/JDI internals hidden by JPMS.
   - `-Xlog:jfr=warning:stdout` silences noisy JFR warnings during tests.

2. **Profiles that add `-javaagent`** – ensure every profile that enables hot reload (e.g., your own `run-with-agent` or `all-tests`) keeps `-XX:+EnableDynamicAgentLoading` in its `argLine` alongside the `-javaagent` flag.

3. **Runtime launches** – when booting your application directly, pass the same flags:

   ```bash
   java \
     -XX:+EnableDynamicAgentLoading \
     --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
     --add-opens jdk.jdi/com.sun.jdi=ALL-UNNAMED \
     --add-opens jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED \
     -javaagent:path/to/descartes-mcp-jar-with-dependencies.jar \
     -jar your-app.jar
   ```

Failing to include these flags typically results in `InaccessibleObjectException` (missing `--add-opens`) or the JVM refusing to load the hot-reload agent (`EnableDynamicAgentLoading` disabled).

### Standalone Usage

The `SimpleMCPServerExample` class demonstrates standalone usage:

```java
// Create settings and context
DefaultSettings settings = new DefaultSettings();
Map<String, Object> context = new HashMap<>();

// Add application objects to context
context.put("myapp.service", myService);
context.put("myapp.repository", myRepository);

// Create and configure server
MCPServer server = new MCPServer(settings, 9080, context);
server.setServerName("My Application MCP Server");
server.setServerVersion("1.0.0");

// Register tools - NOTE: Some tools require context injection!
server.registerTool(new JShellTool(context));           // REQUIRES context
server.registerTool(new JShellSessionTool(context));    // REQUIRES context
server.registerTool(new ObjectInspectorTool(context));  // REQUIRES context
server.registerTool(new ProcessInspectorTool());        // No context needed
server.registerTool(new SystemMonitoringTool());        // No context needed
server.registerTool(new ThreadAnalyzerTool());          // No context needed
server.registerTool(new MemoryAnalyzerTool());          // No context needed
server.registerTool(new ExceptionAnalysisTool());       // No context needed
server.registerTool(new LoggingIntegrationTool());      // No context needed

// Register resources
ResourceRegistry registry = new ResourceRegistry("app");
registry.registerResource(new ClasspathResource());
registry.registerResource(new MetricsResource());
// ... register other resources
server.registerResource(registry);

// Start server
server.start();
```

### Embedding in Your Application

1. **Add Dependency** (when published to Maven Central):
```xml
<dependency>
    <groupId>com.bitsapplied.descartes</groupId>
    <artifactId>descartes-mcp</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

2. **Initialize in Your Application**:
```java
public class MyApplication {
    private MCPServer mcpServer;
    
    public void start() {
        // Your application initialization
        
        // Initialize MCP server
        Map<String, Object> context = new HashMap<>();
        context.put("app", this);
        context.put("dataSource", dataSource);
        
        mcpServer = new MCPServer(new DefaultSettings(), 9080, context);
        // Register tools and resources
        mcpServer.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(mcpServer::stop));
    }
}
```

### Enhancing Claude's Debugging Capabilities with Descartes

When integrating Descartes into your application, you can significantly enhance Claude's ability to debug and understand your code by properly documenting the integration in your project's `CLAUDE.md` file.

#### Using CLAUDE-SECTION.md

This repository includes `CLAUDE-SECTION.md`, a ready-to-use template that teaches Claude how to effectively use Descartes for runtime debugging and introspection. To integrate it:

1. **Copy the template** into your project's CLAUDE.md:
   ```bash
   # From your project root
   cat /path/to/descartes-mcp/CLAUDE-SECTION.md >> CLAUDE.md
   ```

2. **Customize the template** with your application-specific details:
   - Replace the example port (9080) with your actual Descartes server port
   - Update the context object examples with your actual application objects:
     ```markdown
     ### Available Context Objects
     - `context.get("userService")` - User management service
     - `context.get("orderRepository")` - Order data access
     - `context.get("cache")` - Application cache manager
     - `context.get("config")` - Runtime configuration
     ```
   - Specify any restricted operations specific to your environment
   - Add examples of common debugging scenarios in your application

3. **Document your integration pattern**:
   ```markdown
   ## Descartes Integration Details
   - **Port**: 9080
   - **Auto-start**: Descartes starts automatically with the application
   - **Context refresh**: Context objects are updated on application restart
   ```

#### Benefits of Using CLAUDE-SECTION.md

When you properly document Descartes in your CLAUDE.md using the provided template, Claude will:

- **Prioritize runtime debugging** over suggesting code changes
- **Use the appropriate Descartes tool** for each debugging scenario
- **Test fixes in JShell** before modifying code
- **Navigate your application context** effectively
- **Understand security boundaries** and safe operations
- **Follow established debugging workflows** for common issues

#### Example Integration

After adding CLAUDE-SECTION.md to your project's CLAUDE.md, Claude will automatically know to:

```markdown
# When debugging a NullPointerException:
1. Use exception_analysis tool to get the full stack trace
2. Use object_inspector to examine objects in the error path
3. Use jshell_repl to test the code with different inputs
4. Verify the fix works before suggesting code changes

# When investigating performance issues:
1. Check thread_analyzer for blocked threads
2. Review system_monitoring metrics
3. Capture process_inspector_stacks to see executing code
4. Analyze memory_analyzer output for memory pressure
```

This documentation-driven approach ensures Claude uses Descartes effectively as a powerful runtime debugging tool for your application.

### Important Configuration Note: Log4j2 Setup

For the `LoggingIntegrationTool` to capture logs, you need to configure the custom `InMemoryAppender`. When running outside of the test scope, copy `/src/test/resources/log4j2.properties` to your main resources directory, or add these essential lines to your existing `log4j2.properties`:

```properties
# Register the custom appender package
packages = com.bitsapplied.descartes.util

# Configure the In-Memory Appender
appender.inMemory.type = InMemoryAppender
appender.inMemory.name = INMEMORY
appender.inMemory.layout.type = PatternLayout
appender.inMemory.layout.pattern = %d{dd-MM-yyyy HH:mm:ss} %5p %c{1}:%L - %m%n
appender.inMemory.maxBufferSize = 500
appender.inMemory.truncateBackTo = 400
appender.inMemory.loggerFilter = <your application package(s) here>

# Add to root logger appenders
rootLogger.appenderRefs = console, inMemory
rootLogger.appenderRef.inMemory.ref = INMEMORY
```

Without this configuration, the `LoggingIntegrationTool` will not be able to capture and analyze application logs.

## Architecture

### Core Design

Descartes implements the Model Context Protocol (MCP) using a flexible, extensible architecture:

- **Generic Context Pattern**: Tools and resources access application objects through a `Map<String, Object>` context, avoiding tight coupling to specific application types. This allows Descartes to integrate with any Java application without requiring modifications to the application's codebase.

- **JSON-RPC Communication**: The server communicates using JSON-RPC 2.0 protocol over TCP sockets, handling requests for tool execution and resource retrieval. Each client connection is managed in a separate thread for concurrent operation.

- **Plugin Architecture**: Tools implement the `MCPTool` interface and resources implement `MCPResource`, making it easy to add new capabilities without modifying the core server.

### Session Management

The JShell integration provides sophisticated session management:

- **Isolated Execution Contexts**: Each AI conversation gets its own JShell instance with separate variable namespaces, preventing cross-contamination between sessions.
- **Configurable Timeouts**: Sessions automatically expire after inactivity (default: 30 minutes) to prevent memory leaks.
- **State Preservation**: Variables and imports persist across multiple evaluations within the same session, enabling complex multi-step interactions.
- **Context Injection**: Application objects from the context map can be automatically exposed to JShell sessions, allowing direct manipulation of live application state.
- **Concurrent Session Support**: Multiple sessions can run simultaneously without interference.

### Resource Access Pattern

Resources follow a URI-based access pattern with a pluggable registry system:

- **URI Scheme**: Resources use custom URI schemes (default: `app://`) for namespacing
- **Dynamic Discovery**: Resources are discovered at runtime through the registry
- **Read-Only Access**: Resources provide read-only views of system state for safety
- **JSON Serialization**: All resource data is automatically serialized to JSON for transport

Available resource endpoints:
- `app://classpath` - JVM classpath entries and loaded classes
- `app://system-properties` - System and JVM properties
- `app://metrics` - Application performance metrics
- `app://threads` - Thread dumps and thread state analysis
- `app://mbeans` - JMX MBean attributes and operations
- `app://context` - Application-specific objects from the context map

### Security Considerations

⚠️ **CRITICAL WARNING: This tool provides ARBITRARY CODE EXECUTION through multiple features.**

#### JShell REPL Security Risks

The JShell tools (`jshell_repl`, `jshell_session_manager`, `object_inspector`) can execute ANY Java code submitted to them. This is NOT a sandboxed environment - code runs with full JVM permissions and can:
- Access and modify any objects in the application context
- Read/write files on the filesystem
- Make network connections
- Execute system commands via `Runtime.exec()`
- Access sensitive data in memory
- Modify application state at runtime

#### Debugger Security Risks (NEW)

The debugger tools provide **complete JVM control** with the following capabilities:

**Expression Evaluation**:
- Execute arbitrary Java code at breakpoints (same risks as JShell)
- Access private fields and methods
- Invoke any method with arbitrary arguments
- Potentially cause side effects in application state

**JDWP (Java Debug Wire Protocol) Access**:
- Read and modify any memory location in the JVM
- Change method execution flow (skip lines, jump to different locations)
- Force method returns with arbitrary values
- Create and manipulate objects
- Access all loaded classes and their bytecode
- Control thread execution (suspend, resume, step)

**Attack Surface**:
- JDWP port (if exposed) provides **root-equivalent JVM access**
- No authentication or authorization in JDWP protocol
- Expression evaluator can execute malicious code at breakpoints
- Variable inspection can expose sensitive data (passwords, tokens, keys)

#### PRODUCTION WARNING

This server should **NEVER** be used in:
- ❌ Production environments
- ❌ Multi-tenant systems
- ❌ Internet-accessible networks
- ❌ Systems handling sensitive data (PCI, HIPAA, PII)
- ❌ Untrusted networks or with untrusted users

#### RECOMMENDED DEPLOYMENT

**ONLY use Descartes in:**
- ✅ Local development workstations
- ✅ Isolated development containers
- ✅ Secure CI/CD environments (testing only)
- ✅ Internal development networks (with strict access controls)
- ✅ Localhost connections only (default configuration)

**Additional Precautions:**
- Run behind a firewall with strict access controls
- Use VPN/SSH tunneling if network access is required
- Implement authentication/authorization layers if exposed beyond localhost
- Monitor and log all debugging sessions
- Disable debugger features in any non-development environment

**Resource Isolation**: While resources provide read-only access, both JShell and debugger tools can modify any accessible application state

## Example Tool Usage

### JShell REPL
```json
{
  "tool": "jshell_repl",
  "arguments": {
    "code": "System.out.println(\"Hello from JShell!\");",
    "session_id": "session-123"
  }
}
```

### Object Inspector
```json
{
  "tool": "object_inspector",
  "arguments": {
    "expression": "context.get(\"myService\")",
    "operation": "inspect"
  }
}
```

### System Monitoring
```json
{
  "tool": "system_monitoring",
  "arguments": {
    "include_memory": true,
    "include_cpu": true,
    "include_gc": true
  }
}
```

## Development

### Project Structure

```
descartes-mcp/
├── src/main/java/com/bitsapplied/descartes/
│   ├── MCPServer.java              # Core server implementation
│   ├── tools/                      # Tool implementations
│   ├── resources/                  # Resource providers
│   ├── util/                       # Utility classes
│   └── example/                    # Example usage
├── src/test/                       # Test suite
├── config/mcp/                     # MCP client adapter
│   ├── mcp-tcp-adapter.js         # TCP adapter for Claude Desktop
│   ├── mcpservers.json            # Client configuration
│   └── README-adapter.md          # Adapter documentation
└── pom.xml                         # Maven configuration
```

### Testing

The project uses JUnit 5 with comprehensive test coverage and specialized Maven profiles:

**Quick Tests (Default)**
```bash
mvn test  # 414 tests, fast feedback (~1 minute)
```

**Hot Reload Tests** (requires Java agent)
```bash
mvn test -Phot-reload-tests  # 20 hot reload tests
```

**Concurrency Tests** (timing-sensitive tests)
```bash
mvn test -Pconcurrency-tests
```

**Complete Test Suite** (CI/CD)
```bash
mvn test -Pall-tests  # 803 tests including hot reload + concurrency
```

**Test Coverage:**
- ✅ 414 standard tests (core functionality)
- ✅ 20 hot reload tests (class redefinition)
- ✅ 369+ concurrency tests (thread-safety)
- ✅ All tests pass with 0 failures

See [TEST_IMPROVEMENTS.md](TEST_IMPROVEMENTS.md) for detailed testing documentation.

### Contributing

1. Fork the repository
2. Create a feature branch
3. Write tests for new functionality
4. Ensure all tests pass: `mvn test -Pall-tests`
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/widoriezebos/descartes-mcp).
