# GEMINI.md

## Project Overview

This project, Descartes MCP, is a Java-based Model Context Protocol (MCP) server. It provides a suite of tools for deep introspection, monitoring, and debugging of Java applications. The server allows AI assistants to interact with a running Java process, enabling runtime analysis and diagnostics.

**SECURITY NOTE**: The JShell tools provide arbitrary code execution capabilities. This server should only be used in development environments and never exposed to untrusted networks or users in production.

### Key Technologies

*   **Java**: The project is written in Java. The minimum required version is Java 16, but it is configured to use Java 23 for optimal performance.
*   **Maven**: The project is built and managed using Apache Maven.
*   **Model Context Protocol (MCP)**: The server implements the MCP specification for communication with AI assistants.
*   **JShell**: The Java REPL (Read-Eval-Print Loop) is used to provide interactive code execution.
*   **Java Flight Recorder (JFR)**: Used by the performance profiler for low-overhead monitoring (requires JDK 11+).

## Building and Running

The project is built using Maven. The following commands are the most common for building and running the project.

### Build Commands

*   **Build the project**:
    ```bash
    mvn clean compile
    ```
*   **Run tests (excludes concurrency and hot reload tests)**:
    ```bash
    mvn test
    ```
*   **Run concurrency tests only**:
    ```bash
    mvn test -Pconcurrency-tests
    ```
*   **Run hot reload tests only (requires agent)**:
    ```bash
    mvn test -Phot-reload-tests
    ```
*   **Run all tests**:
    ```bash
    mvn test -Pall-tests
    ```
*   **Package the application with dependencies**:
    ```bash
    mvn clean package
    ```

### Running the Server

*   **Run the example server (standard mode)**:
    ```bash
    mvn exec:java
    ```
*   **Run with hot reload support (easiest way)**:
    ```bash
    mvn compile exec:exec -Prun-with-agent
    ```
*   **Run with hot reload using the script**:
    ```bash
    ./scripts/run-with-hotreload.sh
    ```

## Architecture

### Core Components

*   **MCPServer**: The main server implementation that handles JSON-RPC protocol, manages client connections, and routes requests.
*   **Tools**: Implementations of the `MCPTool` interface that provide the core functionality of the server (e.g., `JShellTool`, `ThreadAnalyzerTool`, Profiler tools).
*   **Resources**: Implementations of the `MCPResource` interface that provide read-only access to application and system information (e.g., classpath, system properties).
*   **Hot Reload Subsystem**: Provides runtime class redefinition capabilities, requiring a Java agent.
*   **Performance Profiling Subsystem**: JFR-based low-overhead performance profiling.
*   **Context Map**: A central `Map<String, Object>` for sharing application objects with tools and resources without tight coupling.

### Key Design Patterns

*   **Generic Context Pattern**: Tools and resources access application objects through the generic context map.
*   **Session Management**: JShell sessions have configurable timeouts and are isolated between different AI conversations.
*   **Resource Registry**: URI-based resource access pattern (e.g., `app://classpath`).

## Development Conventions

### Tool Development

Tools should follow a progressive disclosure pattern to avoid overwhelming the user with information. The `ThreadAnalyzerTool` is a good example:
1.  **List**: Provide a lightweight summary.
2.  **Filter**: Allow the user to narrow down the information.
3.  **Inspect**: Provide a detailed view of specific items.

### Testing

The project uses JUnit 5 with separate Maven profiles for different test categories (`concurrency-tests`, `hot-reload-tests`, `all-tests`) to manage test execution and speed up the default build.

### Contribution Guidelines

1.  Fork the repository.
2.  Create a feature branch.
3.  Write tests for new functionality.
4.  Ensure all tests pass: `mvn test -Pall-tests`.
5.  Submit a pull request.

## Git Operations Policy

**IMPORTANT**: As an AI assistant, you must **NEVER** perform any Git operations. This includes, but is not limited to, `git commit`, `git add`, `git push`, `git pull`, `git rebase`, or creating branches. All Git-related activities will be handled exclusively by the human user.