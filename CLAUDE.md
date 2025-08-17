# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Descartes MCP is a Java-based Model Context Protocol (MCP) server that provides deep introspection, monitoring, debugging, and REPL capabilities for Java applications. It enables AI assistants to interact with running Java processes through tools and resources.

**SECURITY NOTE**: The JShell tools provide arbitrary code execution capabilities. This server should only be used in development environments and never exposed to untrusted networks or users in production.

## Build and Development Commands

```bash
# Build the project
mvn clean compile

# Run tests (excludes concurrency tests by default)
mvn test

# Run concurrency tests only
mvn test -Pconcurrency-tests

# Run all tests including concurrency tests
mvn test -Pall-tests

# Package the application with dependencies
mvn clean package

# Run the example server
mvn exec:java

# Build Eclipse-specific output (when using Eclipse IDE)
mvn clean compile -Peclipse-m2e
```

## Architecture

### Core Components

**MCPServer** (`com.bitsapplied.descartes.MCPServer`): Main server implementation that handles JSON-RPC protocol, manages client connections on a configurable port (default 9080), and routes requests to registered tools and resources.

**Tools** (`com.bitsapplied.descartes.tools.*`): Implement the `MCPTool` interface to provide callable functions:
- `JShellTool`: Interactive Java REPL with session management
- `JShellSessionTool`: Manages JShell sessions lifecycle
- `ObjectInspectorTool`: Deep object inspection without code execution
- `ProcessInspectorTool`: Process and thread information
- `SystemMonitoringTool`: System metrics and monitoring
- `ThreadAnalyzerTool`: Thread state and deadlock detection
- `MemoryAnalyzerTool`: Memory usage analysis
- `ExceptionAnalysisTool`: Exception and error analysis
- `LoggingIntegrationTool`: Log4j2 integration for log capture

**Resources** (`com.bitsapplied.descartes.resources.*`): Implement the `MCPResource` interface to expose read-only data:
- `ClasspathResource`: Classpath information
- `SystemPropertiesResource`: JVM system properties
- `MetricsResource`: Application metrics
- `ThreadDumpResource`: Thread dump information
- `MBeanResource`: JMX MBean access
- `ApplicationContextResource`: Access to application context objects

**Context Map**: Central mechanism for sharing application objects between tools/resources without tight coupling. Tools can access application services, repositories, and other components through this context.

### Key Design Patterns

- **Generic Context Pattern**: Tools and resources access application objects through a `Map<String, Object>` context, avoiding direct dependencies
- **Session Management**: JShell sessions have configurable timeouts and isolation between different AI conversation contexts
- **Resource Registry**: URI-based resource access pattern (e.g., `app://classpath`, `app://metrics`)

## Testing Approach

The project uses JUnit 5 with separate test profiles:
- Default tests exclude concurrency tests for faster feedback
- Concurrency tests run in isolation to avoid interference
- Test suite `DescartesTestSuite` organizes all tests

## Java Version

Minimum: Java 16 (uses records, text blocks, and stream.toList())
Configured: Java 23 in pom.xml for optimal performance

## Integration Points

When integrating Descartes into an application:

1. Create a `Map<String, Object>` context with application objects
2. Instantiate `MCPServer` with settings and context
3. Register desired tools and resources
4. Start the server on a chosen port
5. Handle shutdown gracefully with shutdown hooks

### SimpleMCPServerExample

`com.bitsapplied.descartes.example.SimpleMCPServerExample` is a comprehensive example that showcases all available tools and resources. It demonstrates:
- Setting up the MCP server on port 9080
- Registering all built-in tools (JShell, monitoring, debugging)
- Registering all built-in resources (classpath, metrics, thread dumps, etc.)
- Adding sample objects to the context for JShell access
- Proper error handling for port conflicts

**Important Note - Log4j2 Configuration**: When running SimpleMCPServerExample outside of the test scope, you must configure the custom `InMemoryAppender` for the `LoggingIntegrationTool` to work. Either copy `/descartes-mcp/src/test/resources/log4j2.properties` to the main resources directory, or add these essential lines to your `log4j2.properties`:

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
appender.inMemory.loggerFilter = com.bitsapplied.

# Add to root logger
rootLogger.appenderRefs = console, inMemory
rootLogger.appenderRef.inMemory.ref = INMEMORY
```

## MCP Client Configuration

The repository includes a robust TCP adapter client in `/config/mcp/` that enables Claude Desktop (or other MCP clients) to connect to the Descartes MCP server:

### Files in /config/mcp/

- **mcp-tcp-adapter.js**: Node.js TCP adapter that bridges MCP clients to the TCP-based Descartes server
  - Handles automatic reconnection with exponential backoff
  - Queues messages during disconnections
  - Health monitoring with periodic pings
  - Full MCP protocol compliance for reconnections
  
- **mcpservers.json**: Example configuration for Claude Desktop
  - Configure this file with the correct path to mcp-tcp-adapter.js
  - Default configuration connects to localhost:9080
  
- **README-adapter.md**: Comprehensive documentation of the TCP adapter features
  
- **test-adapter-robustness.sh**: Test script to validate adapter reliability
- **test-improved-adapter.sh**: Additional adapter testing
- **test-mcp-handshake.js**: MCP protocol handshake testing

### Setting up Claude Desktop Integration

1. Copy the mcpservers.json to your Claude Desktop configuration directory
2. Update the path in mcpservers.json to point to the actual location of mcp-tcp-adapter.js:
   ```json
   "args": ["/absolute/path/to/descartes-mcp/config/mcp/mcp-tcp-adapter.js"]
   ```
3. Start the Descartes MCP server: `mvn exec:java`
4. The adapter will automatically connect and reconnect as needed

### TCP Adapter Features

- **Infinite reconnection**: Never gives up trying to connect
- **Message queuing**: Buffers messages during disconnections
- **Health monitoring**: Detects and recovers from stale connections
- **Configurable timeouts**: All delays and intervals can be customized via environment variables