# Hot Class Reload Guide for Descartes MCP

## Overview

The Hot Class Reload feature allows you to dynamically reload Java classes at runtime without restarting your application. This is extremely useful during development for rapid iteration and debugging.

## Features

- **Dynamic Class Reloading**: Reload classes without restarting the JVM
- **Change Detection**: Automatically detects changes in class files and JARs
- **Safety Validation**: Validates that changes are compatible with JVM limitations
- **Package Filtering**: Reload only classes matching specific package patterns
- **Detailed Reporting**: Get comprehensive feedback about what was reloaded and why certain classes couldn't be reloaded

## Limitations

Due to JVM constraints, the following changes are NOT supported:
- Adding/removing methods
- Changing method signatures
- Adding/removing fields
- Changing class hierarchy (superclass, interfaces)
- Changing static initializers

Supported changes include:
- Method body modifications
- Changes to method implementations
- Adding/removing/modifying code within existing methods

## Building the Project

First, build the project:

```bash
mvn clean package
```

This will create the main artifact:
- `target/descartes-mcp-jar-with-dependencies.jar` - The application with all dependencies and agent support built-in

## Using the Hot Reload Feature

### Step 1: Start Your Application with the Agent

The hot reload feature requires the Descartes JAR to be loaded as a Java agent at JVM startup. Add the `-javaagent` flag when starting your application:

```bash
# Running the example server with hot reload support
java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar \
     -jar target/descartes-mcp-*-jar-with-dependencies.jar

# Or using Maven exec with the built JAR
java -javaagent:target/descartes-mcp-*-jar-with-dependencies.jar \
     -cp target/descartes-mcp-*-jar-with-dependencies.jar \
     com.bitsapplied.descartes.example.SimpleMCPServerExample
```

### Step 2: Connect with MCP Client

Connect to the Descartes MCP server using Claude Desktop or another MCP client on port 9080 (default).

### Step 3: Use the Hot Reload Tool

Once connected, you can use the `hot_reload_classes` tool to reload classes:

```json
{
  "tool": "hot_reload_classes",
  "arguments": {
    "packageFilter": "com.example.myapp.*"
  }
}
```

#### Tool Parameters

- **packageFilter** (required): Package pattern to match classes for reloading
  - `com.example.*` - All classes in com.example and subpackages
  - `com.example.MyClass` - Specific class only
  
- **force** (optional): Force reload even if no changes detected (default: false)

- **validateOnly** (optional): Only validate if reload is possible without actually reloading (default: false)

### Example Usage Scenarios

#### Reload All Application Classes
```json
{
  "packageFilter": "com.mycompany.myapp.*",
  "force": false
}
```

#### Validate Changes Before Reloading
```json
{
  "packageFilter": "com.mycompany.myapp.*",
  "validateOnly": true
}
```

#### Force Reload Specific Package
```json
{
  "packageFilter": "com.mycompany.myapp.services.*",
  "force": true
}
```

## Integration in Your Application

### Basic Integration

```java
import com.bitsapplied.descartes.MCPServer;
import com.bitsapplied.descartes.tools.HotClassReloadTool;

public class MyApplication {
    public static void main(String[] args) {
        // Create context with your application objects
        Map<String, Object> context = new HashMap<>();
        context.put("app", MyApplication.class);
        
        // Initialize MCP server
        MCPServer server = new MCPServer(new Settings(), 9080, context);
        
        // Register the hot reload tool
        server.registerTool(new HotClassReloadTool(context));
        
        // Start the server
        server.start();
        
        // Your application logic here
    }
}
```

### Running Your Application

```bash
# Build your application
mvn clean package

# Run with hot reload support
java -javaagent:path/to/descartes-mcp-jar-with-dependencies.jar \
     -cp your-app.jar:descartes-mcp-jar-with-dependencies.jar \
     com.yourcompany.YourMainClass
```

## Troubleshooting

### Agent Not Loaded Error

If you see "Hot reload agent not loaded", ensure:
1. The descartes-mcp-jar-with-dependencies.jar exists at the specified path
2. The `-javaagent` flag is correctly specified and points to the same JAR file
3. You're using the jar-with-dependencies version which includes the agent manifest

### Class Not Found

If classes aren't being reloaded:
1. Check that the package filter matches your classes
2. Verify that class files have actually changed
3. Ensure the classes are loaded by the application classloader

### Incompatible Changes

If you get "incompatible changes" errors:
1. Review the limitations section above
2. Ensure you're only changing method bodies
3. Check that method signatures haven't changed

### Validation Failures

Common validation failures and solutions:
- "Method signature changed" - Don't change method parameters or return types
- "Field signature changed" - Don't add/remove fields
- "Superclass changed" - Don't change class inheritance

## Best Practices

1. **Use Validation First**: Always validate changes before attempting reload
2. **Start Small**: Test with individual classes before reloading entire packages
3. **Monitor Logs**: Check application logs for detailed reload information
4. **Development Only**: Use hot reload in development environments only
5. **Backup State**: Save important state before reloading critical classes

## Security Considerations

⚠️ **WARNING**: The hot reload feature provides powerful capabilities that can modify running code. 

- **Never use in production** environments
- **Restrict access** to the MCP server port
- **Use only in isolated** development environments
- **Be aware** that reloaded code has full JVM permissions

## Technical Details

### How It Works

1. **Class Tracking**: The agent tracks all loaded classes and their source locations
2. **Change Detection**: Monitors file timestamps and bytecode changes
3. **Validation**: Uses ASM to analyze bytecode and ensure compatibility
4. **Redefinition**: Uses Java Instrumentation API to redefine classes
5. **Reporting**: Provides detailed feedback about the reload process

### Performance Impact

The agent has minimal performance impact:
- Small memory overhead for tracking loaded classes
- No impact on application performance unless actively reloading
- Reload time depends on number of classes being reloaded

## Example Session

Here's a typical hot reload session:

```
1. Start application with agent:
   java -javaagent:descartes-mcp-jar-with-dependencies.jar -jar myapp.jar

2. Make changes to MyService.java and recompile:
   mvn compile

3. Use MCP client to reload:
   Tool: hot_reload_classes
   Args: {"packageFilter": "com.myapp.services.*"}

4. Response:
   {
     "status": "success",
     "classesAnalyzed": 15,
     "classesChanged": 3,
     "classesReloaded": 3,
     "reloadedClasses": [
       "com.myapp.services.MyService",
       "com.myapp.services.UserService",
       "com.myapp.services.DataService"
     ],
     "reloadTimeMs": 125
   }

5. Test your changes immediately without restart!
```

## Related Tools

The hot reload tool works well with other Descartes tools:
- **JShell Tool**: Test reloaded classes interactively
- **Object Inspector**: Examine reloaded class instances
- **System Monitoring**: Monitor memory impact of reloads

## Testing Hot Reload Functionality

### Running Hot Reload Tests

The project includes comprehensive test coverage for hot reload functionality:

```bash
# Run hot reload tests with agent loaded
mvn test -Phot-reload-tests

# Run all tests including hot reload tests
mvn test -Pall-tests
```

### Test Coverage

The hot reload test suite covers:

1. **Successful Reloads**
   - Method body modifications
   - Compatible bytecode changes
   - Force reload scenarios

2. **Incompatible Changes Detection**
   - Field additions/removals
   - Method signature changes
   - Superclass modifications
   - Interface changes

3. **Edge Cases**
   - Concurrent reload attempts
   - Empty package filters
   - Non-existent classes
   - Validation-only mode

4. **Error Handling**
   - Agent not loaded scenarios
   - Invalid bytecode
   - Security violations

### Test Architecture

The tests use:
- **ASM Library**: For programmatic bytecode modification
- **Test Classes**: Specially designed classes for modification during tests
- **BytecodeModificationUtil**: Utility for creating various bytecode changes
- **Maven Profile**: Configures Surefire to load the agent during test execution

### Writing Custom Hot Reload Tests

To create your own hot reload tests:

1. Ensure your test class name contains "HotReload"
2. Check agent availability before tests:
   ```java
   if (!HotReloadAgent.isAgentLoaded()) {
       // Skip test or handle gracefully
   }
   ```
3. Use ASM for bytecode modifications
4. Test both successful and failure scenarios

## Support

For issues or questions about hot reload:
1. Check the troubleshooting section above
2. Review the example code in `SimpleMCPServerExample`
3. File an issue on the Descartes MCP GitHub repository