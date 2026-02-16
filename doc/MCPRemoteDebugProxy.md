# MCP Remote Debug Proxy

## Overview

The **MCP Remote Debug Proxy** is a standalone application that enables remote debugging of Java applications through the Model Context Protocol (MCP). It acts as a bridge between MCP clients (like Claude Code) and remote Java Virtual Machines (JVMs) running with JDWP (Java Debug Wire Protocol) enabled.

### What is it?

The Remote Debug Proxy is a lightweight, standalone instance of Descartes that:
- Runs as a separate process from the application being debugged
- Connects to remote JVMs via JDWP
- Exposes debugging capabilities through the MCP protocol
- Requires no code changes or dependencies in the target application

### When to Use It?

**Use the Remote Debug Proxy when:**
- ✅ Debugging applications on remote servers (staging, test environments)
- ✅ Debugging containerized applications (Docker, Kubernetes)
- ✅ Debugging applications where you cannot modify the classpath
- ✅ You need pure debugging capabilities without full monitoring
- ✅ Minimal footprint is important (no Descartes embedded in target)

**Use Embedded Descartes when:**
- ✅ Developing locally with full tool access (hot-reload, profiling, monitoring)
- ✅ You need comprehensive observability (logs, metrics, profiling)
- ✅ You control the application startup and can add dependencies

### Architecture

```
┌──────────────────────┐         ┌────────────────────────┐         ┌─────────────────────┐
│   MCP Client         │  MCP    │  MCPRemoteDebugProxy   │  JDWP   │   Target JVM        │
│   (Claude Code)   │◄───────►│  (port 9090)           │◄───────►│   (remote:5005)     │
│                      │  TCP    │                        │  TCP    │                     │
│  • Natural language  │  9090   │  • DebuggerService     │  Socket │  • Your Application │
│  • Debugging tasks   │         │  • Debugger Tools (8)  │         │  • JDWP Agent       │
│  • Code inspection   │         │  • Thread Analyzer     │         │  • Suspended Threads│
└──────────────────────┘         │  • Object Inspector    │         └─────────────────────┘
                                 └────────────────────────┘
```

**Key Points:**
- The proxy runs independently of the target application
- No Descartes JAR required in the target application's classpath
- Only JDWP must be enabled on the target JVM (standard debugging setup)
- Multiple MCP clients can connect to one proxy
- One proxy connects to one target JVM at a time

---

## Quick Start

### Step 1: Start Your Target Application with JDWP

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-application.jar
```

For agent-driven workflows, prefer a supervised non-TTY launch so target lifecycle is not tied to an interactive terminal:

```bash
scripts/launch-managed-nontty.sh \
  --name myapp-debug-target \
  -- java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-application.jar
```

Launch this script without a PTY (`tty=false` in tool-based execution).

**For JDK 17+**, add JPMS flag:
```bash
java --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-application.jar
```

### Step 2: Start the Remote Debug Proxy

**Option A: Explicit Port (Traditional)**

Using the launch script (recommended):
```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

Using Maven:
```bash
mvn compile exec:exec -Prun-remote-proxy \
    -Ddescartes.jdwp.host=localhost \
    -Ddescartes.jdwp.port=5005
```

Using JAR directly:
```bash
java -jar descartes-mcp-jar-with-dependencies.jar proxy \
     --jdwp-host localhost \
     --jdwp-port 5005 \
     --mcp-port 9090
```

**Option B: Auto-Discovery (Zero-Config)** ✨

The proxy can automatically discover and connect to JDWP processes running on the same machine:

```bash
# Auto-discover with pattern (recommended)
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "morpheus"

# Auto-discover using wildcards
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "morpheus*"
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "*-server"

# Auto-discover single process (no pattern needed)
./scripts/run-remote-proxy.sh --auto-discover
```

**When to use auto-discovery:**
- ✅ Local development - no need to remember JDWP ports
- ✅ Multiple applications - use patterns to select the right one
- ✅ Dynamic ports - when JDWP ports change between runs
- ❌ Remote debugging - use explicit host/port instead

### Step 3: Connect Your MCP Client

Configure Claude Code or your MCP client to connect to `localhost:9090`. The proxy is now ready to relay debugging commands to your target application.

---

## Prerequisites

### Required

- **JDK 11+** - To run the proxy (JDK 17+ recommended for best compatibility)
- **JDWP-enabled target** - Target JVM must be launched with `-agentlib:jdwp=...`
- **Network access** - Proxy must be able to reach target JVM on JDWP port

### Optional

- **Maven** - For building from source and using Maven launch profiles
- **Docker** - For containerized deployments
- **Kubernetes** - For debugging pods

---

## Configuration

The proxy supports three configuration sources with clear precedence:

**Priority (highest to lowest):**
1. **Command-line arguments** - Override everything
2. **Configuration file** (`proxy-config.json`) - Override environment variables
3. **Environment variables** - Override defaults
4. **Built-in defaults** - Fallback values

### Configuration Parameters

| Parameter | CLI Argument | Environment Variable | Config File Key | Default | Description |
|-----------|-------------|---------------------|-----------------|---------|-------------|
| JDWP Host | `--jdwp-host` | `DESCARTES_JDWP_HOST` | `jdwpHost` | `localhost` | Target JVM hostname or IP |
| JDWP Port | `--jdwp-port` | `DESCARTES_JDWP_PORT` | `jdwpPort` | `5005` | Target JDWP port |
| **Auto-Discovery** | `--auto-discover` | `DESCARTES_AUTO_DISCOVER` | `autoDiscover` | `false` | **Enable automatic JDWP process discovery** |
| **Process Pattern** | `--process-pattern <pattern>` | `DESCARTES_PROCESS_PATTERN` | `processPattern` | `null` | **Pattern to match process name (supports * and ? wildcards)** |
| MCP Port | `--mcp-port` | `DESCARTES_MCP_PORT` | `mcpPort` | `9090` | MCP server listening port |
| JDWP Timeout | `--jdwp-timeout` | `DESCARTES_JDWP_TIMEOUT` | `jdwpTimeout` | `5000` | JDWP connection timeout (ms) |
| Reconnect | `--reconnect` | `DESCARTES_RECONNECT` | `reconnectEnabled` | `true` | Auto-reconnect on connection loss |
| Reconnect Interval | `--reconnect-interval` | `DESCARTES_RECONNECT_INTERVAL` | `reconnectIntervalMs` | `5000` | Reconnection attempt interval (ms) |
| Health Check Interval | `--health-check-interval` | `DESCARTES_HEALTH_CHECK_INTERVAL` | `healthCheckIntervalMs` | `30000` | Health check interval (ms) |
| Config File | `--config` | `DESCARTES_CONFIG_FILE` | N/A | `./proxy-config.json` | Path to configuration file |

### Configuration Methods

#### 1. Command-Line Arguments

**Explicit configuration:**
```bash
./scripts/run-remote-proxy.sh \
    --jdwp-host staging.example.com \
    --jdwp-port 5005 \
    --mcp-port 9090 \
    --jdwp-timeout 10000 \
    --reconnect true \
    --reconnect-interval 5000
```

**Auto-discovery configuration:**
```bash
# Pattern-based discovery (recommended)
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp"

# Wildcard patterns
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp*"
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "*-production"

# Single process auto-select
./scripts/run-remote-proxy.sh --auto-discover
```

#### 2. Configuration File (JSON)

**Traditional explicit config** (`proxy-config.json`):
```json
{
  "jdwpHost": "staging.example.com",
  "jdwpPort": 5005,
  "mcpPort": 9090,
  "jdwpTimeout": 10000,
  "reconnectEnabled": true,
  "reconnectIntervalMs": 5000,
  "healthCheckIntervalMs": 30000
}
```

**Auto-discovery config** (`proxy-config-auto.json`):
```json
{
  "autoDiscover": true,
  "processPattern": "morpheus",
  "mcpPort": 9090,
  "jdwpTimeout": 5000,
  "reconnectEnabled": true
}
```

Then start with:
```bash
./scripts/run-remote-proxy.sh --config proxy-config.json
```

#### 3. Environment Variables

**Explicit configuration:**
```bash
export DESCARTES_JDWP_HOST=staging.example.com
export DESCARTES_JDWP_PORT=5005
export DESCARTES_MCP_PORT=9090
export DESCARTES_RECONNECT=true

./scripts/run-remote-proxy.sh
```

**Auto-discovery configuration:**
```bash
export DESCARTES_AUTO_DISCOVER=true
export DESCARTES_PROCESS_PATTERN="morpheus"
export DESCARTES_MCP_PORT=9090

./scripts/run-remote-proxy.sh
```

#### 4. Hybrid Approach (Recommended)

Use config file for stable settings, CLI for overrides:

```bash
# config-base.json has common settings
./scripts/run-remote-proxy.sh --config config-base.json --jdwp-host localhost

# Or override auto-discovery pattern
./scripts/run-remote-proxy.sh --config config-auto.json --process-pattern "different-app"
```

---

## Auto-Discovery

### Overview

Auto-discovery eliminates the need to manually specify JDWP ports by automatically finding Java processes running in debug mode on the same machine. This is especially useful for:
- **Local development**: No need to remember which application uses which port
- **Multiple applications**: Use patterns to disambiguate
- **Dynamic environments**: Works even when JDWP ports change between runs

### How It Works

The proxy uses the Java Attach API (`com.sun.tools.attach.VirtualMachine`) to:
1. List all JVM processes running as the same user
2. Attach to each process and inspect its JDWP configuration
3. Match process names against your pattern (if provided)
4. Connect to the discovered JDWP port automatically

**Security Note**: Auto-discovery only finds processes running as the same operating system user. This is a security feature that prevents unauthorized access to other users' Java processes.

### Usage Patterns

#### Pattern Syntax

Auto-discovery supports simple wildcard patterns:
- `*` - Matches any number of characters
- `?` - Matches exactly one character
- Case-insensitive matching

**Examples:**
| Pattern | Matches | Doesn't Match |
|---------|---------|---------------|
| `morpheus` | Morpheus, MORPHEUS | morpheus-server |
| `morpheus*` | morpheus-server, Morpheus Application | neo-morpheus |
| `*server` | myapp-server, api-server | server-app |
| `app-?-prod` | app-1-prod, app-A-prod | app-10-prod |

#### Matching Strategy

When you provide a pattern, the proxy uses this strategy:
1. **Exact match** (case-insensitive) - Checks if process name equals pattern exactly
2. **Wildcard match** - If no exact match, tries wildcard pattern matching
3. **First match wins** - If multiple processes match, connects to the first discovered
4. **Helpful errors** - If no matches, lists all available JDWP processes

### Examples

#### Single Application

When only one JDWP process is running:
```bash
./scripts/run-remote-proxy.sh --auto-discover
# ✅ Auto-selects the only process found
```

#### Multiple Applications with Pattern

```bash
# Development environment with multiple apps
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "order-service"

# Production-like names
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "*-production"

# Using wildcards for flexibility
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp*"
```

#### Environment Variables

Set once, use everywhere:
```bash
export DESCARTES_AUTO_DISCOVER=true
export DESCARTES_PROCESS_PATTERN="myapp"

# No need to specify flags
./scripts/run-remote-proxy.sh
```

#### Configuration File

Create `auto-discovery.json`:
```json
{
  "autoDiscover": true,
  "processPattern": "morpheus",
  "mcpPort": 9090
}
```

Use it:
```bash
./scripts/run-remote-proxy.sh --config auto-discovery.json
```

### Troubleshooting

**No processes found:**
```
Error: No Java debug processes found on this machine.
Ensure the target JVM is running with -agentlib:jdwp=... enabled.
```

**Solution**: Start your application with JDWP:
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar your-app.jar
```

**Pattern doesn't match:**
```
Error: No debug process found matching pattern: 'myap'

Available debug processes:
  - myapp-server (PID: 12345, port: 5005)
  - other-app (PID: 67890, port: 5006)
```

**Solution**: Fix your pattern or use one of the listed process names.

**Multiple matches without pattern:**
```
Error: Multiple JDWP processes found. Please specify --process-pattern to select one:
  - myapp-server (PID: 12345, port: 5005)
  - myapp-worker (PID: 23456, port: 5006)
```

**Solution**: Add a pattern to disambiguate:
```bash
./scripts/run-remote-proxy.sh --auto-discover --process-pattern "myapp-server"
```

### Limitations

- **Local only**: Auto-discovery only works on the same machine as the proxy
- **Same user**: Only discovers processes running as the same OS user
- **JDWP required**: Target process must have JDWP enabled at startup
- **Not for remote**: For remote debugging, use explicit `--jdwp-host` and `--jdwp-port`

---

## Launch Methods

### Method 1: Launch Script (Recommended)

The `scripts/run-remote-proxy.sh` script provides the simplest way to start the proxy:

```bash
# Build if needed and start proxy
./scripts/run-remote-proxy.sh --jdwp-port 5005

# Pass any configuration arguments
./scripts/run-remote-proxy.sh \
    --jdwp-host 192.168.1.100 \
    --jdwp-port 5005 \
    --mcp-port 9091

# Use config file
./scripts/run-remote-proxy.sh --config my-proxy-config.json

# Override specific settings from config
./scripts/run-remote-proxy.sh --config prod.json --jdwp-host staging.example.com
```

**What it does:**
- Checks if build is up-to-date (runs `mvn package` if needed)
- Sets proper JVM flags (including `--add-opens` for JDK 17+)
- Passes arguments to the proxy
- Provides clean shutdown on Ctrl+C

### Method 2: Maven Profile

Use Maven directly for development:

```bash
# Basic usage
mvn compile exec:exec -Prun-remote-proxy

# With system properties
mvn compile exec:exec -Prun-remote-proxy \
    -Ddescartes.jdwp.host=localhost \
    -Ddescartes.jdwp.port=5005 \
    -Ddescartes.mcp.port=9090

# With config file
mvn compile exec:exec -Prun-remote-proxy \
    -Ddescartes.config.file=./proxy-config.json
```

### Method 3: Direct JAR Execution

For production deployments or custom setups:

```bash
# Using the executable JAR
java -jar target/descartes-mcp-jar-with-dependencies.jar proxy \
     --jdwp-host localhost \
     --jdwp-port 5005 \
     --mcp-port 9090

# For JDK 17+, add JPMS flag
java --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -jar target/descartes-mcp-jar-with-dependencies.jar proxy \
     --jdwp-host localhost \
     --jdwp-port 5005

# With config file
java -jar descartes-mcp.jar proxy --config /etc/descartes/proxy-config.json
```

### Method 4: Containerized Deployment

See [Docker Deployment](#docker-deployment) and [Kubernetes Deployment](#kubernetes-deployment) sections below.

---

## Connection Patterns

### Pattern 1: Local Debugging (Same Machine)

**Use Case:** Debug application running on your local development machine.

**Setup:**

Terminal 1 - Start target application:
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar my-app.jar
```

Terminal 2 - Start proxy:
```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

Terminal 3 - Use MCP client to connect to `localhost:9090`

**Advantages:**
- Simple setup
- No network configuration needed
- Low latency (<1ms)

### Pattern 2: Remote Debugging (Network)

**Use Case:** Debug application running on a remote server (staging, test environment).

**Setup:**

On remote server (`staging.example.com`):
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar my-app.jar
```

On your local machine:
```bash
./scripts/run-remote-proxy.sh \
    --jdwp-host staging.example.com \
    --jdwp-port 5005 \
    --jdwp-timeout 10000
```

**⚠️ Security Warning:**
- JDWP provides **full control** over the target JVM
- **Never expose JDWP ports publicly**
- Use firewall rules to restrict JDWP access
- Consider SSH tunneling (see below) for production environments

**Network Requirements:**
- Proxy must be able to reach target on specified port
- Firewall must allow traffic on JDWP port (5005)
- Latency: Typically 10-100ms depending on distance

### Pattern 3: SSH Tunnel (Secure Remote Debugging)

**Use Case:** Securely debug remote application without exposing JDWP port.

**Setup:**

Create SSH tunnel to remote server:
```bash
ssh -L 5005:localhost:5005 user@staging.example.com -N
```

This forwards local port 5005 to remote server's localhost:5005.

Start proxy connecting to local end of tunnel:
```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

On remote server, JDWP should listen on localhost only:
```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005 \
     -jar my-app.jar
```

**Advantages:**
- JDWP port not exposed to network
- Traffic encrypted through SSH
- Firewall-friendly (only SSH port needed)

### Pattern 4: Docker Container Debugging

**Use Case:** Debug application running in Docker container.

**Dockerfile:**
```dockerfile
FROM openjdk:17-jdk-slim

# Copy application
COPY target/my-app.jar /app/my-app.jar

# Expose application port and JDWP port
EXPOSE 8080 5005

# Start with JDWP enabled
ENTRYPOINT ["java", \
  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", \
  "-jar", "/app/my-app.jar"]
```

**Run container with port mapping:**
```bash
docker run -p 8080:8080 -p 5005:5005 my-app:latest
```

**Start proxy on host machine:**
```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

**Docker Compose Example:**
```yaml
version: '3.8'
services:
  app:
    image: my-app:latest
    ports:
      - "8080:8080"  # Application port
      - "5005:5005"  # JDWP port
    environment:
      JAVA_OPTS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

### Pattern 5: Kubernetes Pod Debugging

**Use Case:** Debug application running in Kubernetes pod.

**Deployment YAML:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  replicas: 1
  template:
    spec:
      containers:
      - name: app
        image: my-app:latest
        env:
        - name: JAVA_OPTS
          value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
        ports:
        - containerPort: 8080
          name: http
        - containerPort: 5005
          name: jdwp
```

**Option A: Port Forwarding (Development)**

Find the pod name:
```bash
kubectl get pods -l app=my-app
```

Forward JDWP port to localhost:
```bash
kubectl port-forward pod/my-app-pod-12345 5005:5005
```

Start proxy:
```bash
./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
```

**Option B: Service Exposure (Team Debugging)**

Create a Service to expose JDWP port:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-app-debug
spec:
  type: LoadBalancer  # Or NodePort for internal access
  selector:
    app: my-app
  ports:
  - name: jdwp
    port: 5005
    targetPort: 5005
```

Get the external IP:
```bash
kubectl get service my-app-debug
```

Start proxy with external IP:
```bash
./scripts/run-remote-proxy.sh --jdwp-host <EXTERNAL-IP> --jdwp-port 5005
```

**⚠️ Production Warning:**
- **Never expose JDWP in production** (full JVM control)
- Use port-forward for temporary debugging only
- Remove or disable JDWP in production deployments
- Consider network policies to restrict JDWP access

---

## Supported Tools

The Remote Debug Proxy exposes only tools that work via JDWP (Java Debug Interface). Tools requiring in-process access are not available.

### ✅ Fully Supported (Via JDWP)

| Tool | Operations | Description |
|------|-----------|-------------|
| **debugger_session** | start, stop, status, threads, suspend, resume, resume_all | Manage debug session lifecycle |
| **debugger_breakpoints** | set, upsert, resolve_line, remove, list, enable, disable | Breakpoint management |
| **debugger_step** | step_over, step_into, step_out | Step through code execution |
| **debugger_threads** | list, inspect, suspend, resume, resume_all | Thread inspection and control |
| **debugger_variables** | get_variables, get_child_variables, get_static_fields | Variable inspection at breakpoints |
| **debugger_stacktrace** | capture, capture_filtered, get_frame | Call stack analysis |
| **debugger_watch** | add, remove, list, enable, disable, evaluate | Watch expression management |
| **debugger_evaluate** | evaluate | Evaluate expressions in debuggee context |
| **debugger_events** | wait (aliases: wait_for, wait_for_event), fetch (alias: get_events), clear | Poll debugger event notifications |
| **thread_analyzer** | thread_list, thread_inspect, thread_search, deadlocks, thread_dump | Advanced thread analysis |
| **object_inspector** | inspect, fields, methods, type, value | Deep object inspection |

**Why These Work:**
- All operations use JDI (Java Debug Interface) API
- JDI operates entirely over JDWP socket connection
- No in-process access or local filesystem required
- Identical functionality to embedded mode

### ❌ Not Available (Require In-Process Access)

| Tool | Why Not Available |
|------|------------------|
| **jshell_repl** | Requires JShell instance running in target JVM process |
| **jshell_async** | Same as jshell_repl - needs in-process JShell execution |
| **jshell_session_manager** | Manages in-process JShell sessions |
| **hot_reload_classes** | Requires Java agent (`-javaagent`) in target JVM |
| **system_monitoring** | Requires local JMX access to target JVM's MBeans |
| **memory_analyzer** | Requires local access to MemoryMXBean and GC APIs |
| **log_file_discovery/search** | Requires local filesystem access to log files and Log4j2 configuration |
| **profiler_start/stop/hotspots/calltree** | Requires JFR (Java Flight Recorder) local access or file system |

**Why In-Process Access Required:**
- JShell requires running JShell interpreter in target JVM
- Hot reload needs Instrumentation API from Java agent
- Monitoring needs direct MBean access (not available via JDWP)
- Profiling needs JFR control and recording file access
- Log file access needs local filesystem access to log files

### Tool Capability Matrix

| Capability | Via JDWP (Proxy) | Via In-Process (Embedded) |
|------------|------------------|---------------------------|
| **Set breakpoints** | ✅ Full support | ✅ Full support |
| **Step through code** | ✅ Full support | ✅ Full support |
| **Inspect variables** | ✅ Full support | ✅ Full support |
| **Evaluate expressions** | ✅ Full support (limited to debuggee context) | ✅ Full support |
| **Thread analysis** | ✅ Full support via JDI ThreadReference | ✅ Full support |
| **Deadlock detection** | ✅ Full support | ✅ Full support |
| **Object inspection** | ✅ Full support via JDI ObjectReference | ✅ Full support |
| **Execute arbitrary code** | ❌ Not available | ✅ Via JShell |
| **Hot-reload classes** | ❌ Not available | ⚠️ Requires `-javaagent` |
| **Memory profiling** | ⚠️ Limited (basic via JDI) | ✅ Full JMX access |
| **CPU profiling** | ❌ Not available | ✅ Via JFR |
| **Log inspection** | ❌ Not available | ✅ Via log files |
| **Exception tracking** | ❌ Not available | ✅ Via exception buffer |

**Legend:**
- ✅ Full support - Complete functionality available
- ⚠️ Limited - Partial functionality or requires additional setup
- ❌ Not available - Cannot be provided via this mode

---

## Configuration Examples

### Example 1: Minimal Local Debugging

**proxy-config.json:**
```json
{
  "jdwpHost": "localhost",
  "jdwpPort": 5005,
  "mcpPort": 9090
}
```

**Launch:**
```bash
./scripts/run-remote-proxy.sh --config proxy-config.json
```

### Example 2: Remote Production Debugging (SSH Tunnel)

**proxy-config-prod.json:**
```json
{
  "jdwpHost": "localhost",
  "jdwpPort": 5005,
  "mcpPort": 9090,
  "jdwpTimeout": 15000,
  "reconnectEnabled": true,
  "reconnectIntervalMs": 10000,
  "healthCheckIntervalMs": 60000
}
```

**Setup:**
```bash
# Create SSH tunnel first
ssh -L 5005:localhost:5005 user@prod.example.com -N &

# Start proxy
./scripts/run-remote-proxy.sh --config proxy-config-prod.json
```

### Example 3: Docker Compose Setup

**docker-compose.yml:**
```yaml
version: '3.8'

services:
  app:
    image: my-app:latest
    ports:
      - "8080:8080"
      - "5005:5005"
    environment:
      JAVA_OPTS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    networks:
      - app-network

  debug-proxy:
    image: descartes-mcp:latest
    ports:
      - "9090:9090"
    environment:
      DESCARTES_JDWP_HOST: app
      DESCARTES_JDWP_PORT: 5005
      DESCARTES_MCP_PORT: 9090
    depends_on:
      - app
    networks:
      - app-network

networks:
  app-network:
    driver: bridge
```

**Launch:**
```bash
docker-compose up
# MCP client connects to localhost:9090
```

### Example 4: Kubernetes Deployment

**descartes-proxy-deployment.yaml:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: descartes-proxy
  namespace: debugging
spec:
  replicas: 1
  selector:
    matchLabels:
      app: descartes-proxy
  template:
    metadata:
      labels:
        app: descartes-proxy
    spec:
      containers:
      - name: proxy
        image: descartes-mcp:latest
        ports:
        - containerPort: 9090
          name: mcp
        env:
        - name: DESCARTES_JDWP_HOST
          value: "my-app-service.default.svc.cluster.local"
        - name: DESCARTES_JDWP_PORT
          value: "5005"
        - name: DESCARTES_MCP_PORT
          value: "9090"
        - name: DESCARTES_RECONNECT
          value: "true"
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "512Mi"
            cpu: "500m"

---
apiVersion: v1
kind: Service
metadata:
  name: descartes-proxy
  namespace: debugging
spec:
  type: LoadBalancer
  selector:
    app: descartes-proxy
  ports:
  - port: 9090
    targetPort: 9090
    name: mcp
```

**Deploy:**
```bash
kubectl apply -f descartes-proxy-deployment.yaml

# Get external IP
kubectl get service descartes-proxy -n debugging

# Configure MCP client to connect to <EXTERNAL-IP>:9090
```

---

## Troubleshooting

### Connection Refused

**Symptom:** Proxy fails to connect to target JVM with "Connection refused" error.

**Possible Causes & Solutions:**

1. **JDWP not enabled on target**
   ```bash
   # Verify target is running with JDWP
   ps aux | grep jdwp
   # Should show: -agentlib:jdwp=...
   ```

   **Solution:** Restart target with JDWP flags.

2. **Wrong host/port**
   ```bash
   # Verify JDWP is listening
   netstat -an | grep 5005
   # Or on Mac: lsof -i :5005
   ```

   **Solution:** Check `--jdwp-host` and `--jdwp-port` match target.

3. **Firewall blocking connection**
   ```bash
   # Test connectivity
   telnet <jdwp-host> <jdwp-port>
   ```

   **Solution:** Open firewall port or use SSH tunnel.

4. **JDWP bound to localhost only**
   - If target uses `address=localhost:5005`, only local connections allowed

   **Solution:** Change to `address=*:5005` or use SSH tunnel.

### Timeout Errors

**Symptom:** "JDWP connection timed out" or "Operation timed out".

**Solutions:**

1. **Increase timeout:**
   ```bash
   ./scripts/run-remote-proxy.sh \
       --jdwp-host remote.example.com \
       --jdwp-port 5005 \
       --jdwp-timeout 30000  # 30 seconds
   ```

2. **Check network latency:**
   ```bash
   ping remote.example.com
   ```

   High latency (>100ms) may require longer timeouts.

3. **Verify target is responsive:**
   ```bash
   # Try connecting with jdb
   jdb -attach remote.example.com:5005
   ```

4. **Differentiate adapter timeout vs debugger wait timeout:**
   - If you call `debugger_events` with a long `timeout_ms` (for example `120000`) but still get `Request timeout after 30000ms`, the timeout came from the MCP adapter request deadline.
   - Use canonical `operation=wait` (aliases `wait_for` and `wait_for_event` are supported for compatibility), and ensure adapter timeout extension applies to `debugger_events` even when tool names are namespaced (`debugger_events`, `descartes.debugger_events`, `descartes/debugger_events`).
   - If needed, raise adapter `MCP_REQUEST_TIMEOUT` so it is at least as large as expected waits plus network overhead.

5. **Avoid stale queue noise without clearing:**
   - `debugger_events` responses include `latest_sequence`; pass that value back as `since_sequence` in the next `wait`/`fetch` call to ignore older events.
   - Keep `types=["debugger.breakpoint_hit"]` when you are waiting for breakpoint stops.
   - The queue is bounded and prefers evicting low-priority lifecycle events first, but cursor-based polling is still the most reliable workflow.

### Port Already in Use

**Symptom:** "Address already in use" when starting proxy.

**Cause:** Another process using MCP port (default: 9090).

**Solutions:**

1. **Find process using port:**
   ```bash
   lsof -i :9090
   # Kill if safe
   kill <PID>
   ```

2. **Use different port:**
   ```bash
   ./scripts/run-remote-proxy.sh --mcp-port 9091
   ```

### Health Check Failures

**Symptom:** "Health check failed" warnings or auto-reconnect attempts.

**Causes:**
- Target JVM crashed or was restarted
- Network interruption
- JDWP agent became unresponsive

**Solutions:**

1. **Check target is running:**
   ```bash
   ps aux | grep java
   ```

2. **Verify JDWP still listening:**
   ```bash
   lsof -i :5005
   ```

3. **Adjust health check interval:**
   ```bash
   ./scripts/run-remote-proxy.sh \
       --health-check-interval 60000  # Check every 60s instead of 30s
   ```

4. **Disable auto-reconnect if not wanted:**
   ```bash
   ./scripts/run-remote-proxy.sh --reconnect false
   ```

### JDK Version Compatibility

**Symptom:** "Illegal reflective access" warnings or "module does not export" errors.

**Cause:** JDK 17+ requires explicit module opens for JDI access.

**Solution:** Add `--add-opens` flag when starting proxy:

```bash
java --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED \
     -jar descartes-mcp.jar proxy --jdwp-port 5005
```

The `scripts/run-remote-proxy.sh` script includes this automatically.

### Tools Not Working

**Symptom:** MCP client says tool is not available or returns error.

**Cause:** Tool requires in-process access (not available via JDWP).

**Solution:** Check [Supported Tools](#supported-tools) section. Only debugger_*, thread_analyzer, and object_inspector work via proxy.

**Example:**
- ❌ `jshell_repl` → Not available (needs in-process JShell)
- ✅ `debugger_evaluate` → Use this instead for expression evaluation

---

## Advanced Topics

### Health Monitoring and Auto-Reconnect

The proxy includes built-in health monitoring to detect connection issues:

**Health Check Mechanism:**
1. Periodic checks (default: every 30 seconds)
2. Reconnect checks are skipped when:
   - No active debugger session config exists yet
   - A manual `debugger_session start/stop` operation is in progress
3. A session is considered healthy when state is `READY` and JDWP transport health check passes

**Auto-Reconnect Behavior:**
1. Connection loss detected by health check
2. First reconnect is scheduled immediately
3. Subsequent retries use fixed cadence from `--reconnect-interval` (minimum 1000ms)
4. Reconnect attempts are skipped while manual `debugger_session start/stop` is running
5. Reconnect attempts are skipped when session is already `CONNECTING`
6. Continues indefinitely until connection restored

**Configuration:**
```bash
./scripts/run-remote-proxy.sh \
    --reconnect true \
    --reconnect-interval 5000 \
    --health-check-interval 30000
```

**Use Cases:**
- ✅ Target JVM restarts (e.g., during rolling update)
- ✅ Network interruptions
- ✅ JDWP agent temporarily unresponsive

**Manual session operations:**
- `debugger_session start` and `debugger_session stop` temporarily pause auto-reconnect while the manual operation runs.
- This prevents background health checks/reconnect attempts from racing with explicit user start/stop calls.

### Security Considerations

**⚠️ CRITICAL: JDWP Provides Full JVM Control**

JDWP allows complete control over the target JVM:
- Execute arbitrary code
- Read/modify any data in memory
- Suspend/resume threads
- Trigger garbage collection
- Crash the JVM

**Security Best Practices:**

1. **Never expose JDWP publicly:**
   ```bash
   # ❌ DANGEROUS: Exposed to internet
   -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005

   # ✅ SAFE: Localhost only
   -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005
   ```

2. **Use SSH tunneling for remote debugging:**
   ```bash
   ssh -L 5005:localhost:5005 user@remote-server -N
   ```

3. **Disable JDWP in production:**
   - Only enable temporarily for debugging
   - Use feature flags or environment variables to control

4. **Restrict network access:**
   - Use firewall rules to limit JDWP port access
   - Consider VPN for remote debugging

5. **Audit logging:**
   - Log all debugging sessions
   - Monitor who connects and when

6. **Time-limited access:**
   - Enable JDWP only when needed
   - Restart without JDWP after debugging session

**Docker Security:**
```dockerfile
# Production: No JDWP
ENV JAVA_OPTS=""

# Debug mode: Enable JDWP (controlled by environment variable)
ENV JAVA_OPTS="${DEBUG_MODE:+-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005}"
```

**Kubernetes Security:**
```yaml
# Use separate debug deployment, not production
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app-debug
  namespace: debugging  # Separate namespace
spec:
  replicas: 1  # Single debug instance
  template:
    metadata:
      labels:
        app: my-app-debug
    spec:
      containers:
      - name: app
        env:
        - name: JAVA_OPTS
          value: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

### Performance Implications

**Proxy Overhead:**
- CPU: <1% on proxy (minimal)
- Memory: ~100-200MB (JVM overhead + MCP server)
- Network: 1-10 KB/s idle, 100 KB/s during active debugging

**Target JVM Overhead:**
- JDWP idle: ~2-5% CPU, ~10MB memory
- JDWP active debugging: 10-30% CPU (when suspended/stepping)
- Memory impact: Minimal when not suspended

**Network Latency:**
- Local (localhost): <1ms per operation
- Same datacenter: 1-10ms per operation
- Remote: 10-100ms per operation (depends on distance)

**Recommendations:**
- ✅ Use same-host or same-datacenter for interactive debugging
- ✅ Use remote only for investigation/analysis
- ⚠️ Avoid debugging over high-latency connections (>100ms)
- ⚠️ Limit active debugging duration (unsuspend threads when done)

---

## Comparison: Embedded vs Proxy Mode

| Aspect | Embedded Mode | Proxy Mode |
|--------|---------------|-----------|
| **Deployment** | Descartes JAR in target classpath | Standalone Descartes process |
| **Setup** | Add dependency, initialize in code | Start proxy, point to JDWP port |
| **Target Requirements** | JDWP enabled + Descartes dependency | JDWP enabled only |
| **Network** | No network (internal) | TCP socket to target |
| **Tools Available** | All tools (11+ tools) | JDWP-compatible only (11 tools) |
| **JShell REPL** | ✅ Available | ❌ Not available |
| **Hot Reload** | ✅ Available (with -javaagent) | ❌ Not available |
| **Profiling** | ✅ Available (JFR) | ❌ Not available |
| **Monitoring** | ✅ Available (JMX) | ❌ Limited |
| **Debugging** | ✅ Full support | ✅ Full support |
| **Logging** | ✅ Available (log files) | ❌ Not available |
| **Footprint** | +10-20MB in target JVM | Separate process (~200MB) |
| **Use Cases** | Local development, comprehensive observability | Remote debugging, containers, staging/production |
| **Isolation** | Coupled with target application | Independent processes |
| **Production Suitability** | ⚠️ Adds dependencies to production | ✅ No impact on production classpath |

**Decision Guide:**

**Choose Embedded Mode when:**
- Developing locally with full control
- Need comprehensive tooling (REPL, profiling, hot-reload)
- Want single-process simplicity
- Can modify target application dependencies

**Choose Proxy Mode when:**
- Debugging remote applications
- Debugging containers or Kubernetes pods
- Cannot modify target application (third-party, legacy)
- Want minimal target footprint
- Need to debug production-like environments

---

## Integration with Claude Code

### Step 1: Configure MCP Server

Add to Claude Code's MCP configuration (`~/.claude/mcp_servers.json` or similar):

```json
{
  "mcpServers": {
    "descartes-proxy": {
      "command": "node",
      "args": ["/path/to/mcp-tcp-adapter.js"],
      "env": {
        "MCP_HOST": "localhost",
        "MCP_PORT": "9090",
        "SERVER_NAME": "descartes-proxy"
      }
    }
  }
}
```

### Step 2: Start Proxy

```bash
./scripts/run-remote-proxy.sh --jdwp-host <target-host> --jdwp-port 5005
```

### Step 3: Connect Claude Code

Restart Claude Code. The `descartes-proxy` MCP server should appear as available.

### Step 4: Start Debugging

In Claude Code:
```
Debug the application running on staging.example.com:
1. Start a debug session connecting to the JDWP port
2. Set a breakpoint in UserService.createUser() at line 42
3. When the breakpoint hits, inspect the userData variable
```

Claude will use the proxy to relay debugging commands to your remote application.

---

## Example Workflows

### Workflow 1: Debug Production Issue on Staging Server

**Scenario:** Bug reported in production, need to reproduce and debug on staging.

**Steps:**

1. **Enable JDWP on staging server:**
   ```bash
   ssh user@staging.example.com
   sudo systemctl stop myapp
   sudo systemctl edit myapp  # Add JDWP to ExecStart
   sudo systemctl start myapp
   ```

2. **Create SSH tunnel (secure):**
   ```bash
   ssh -L 5005:localhost:5005 user@staging.example.com -N &
   ```

3. **Start proxy:**
   ```bash
   ./scripts/run-remote-proxy.sh \
       --jdwp-host localhost \
       --jdwp-port 5005 \
       --reconnect true
   ```

4. **Use Claude to investigate:**
   ```
   Set breakpoint in PaymentProcessor.processPayment() at line 156.
   When it hits, inspect the paymentRequest object and check if amount is negative.
   ```

5. **Identify issue, fix, deploy:**
   - Claude identifies root cause
   - Fix code locally
   - Deploy to staging
   - Verify fix

6. **Clean up:**
   ```bash
   kill %1  # Stop SSH tunnel
   Ctrl+C in proxy terminal
   ```

### Workflow 2: Debug Kubernetes Microservice

**Scenario:** Microservice in Kubernetes throwing intermittent errors.

**Steps:**

1. **Find pod to debug:**
   ```bash
   kubectl get pods -l app=payment-service
   # payment-service-7d4b8c6f9-x7k2m
   ```

2. **Forward JDWP port:**
   ```bash
   kubectl port-forward payment-service-7d4b8c6f9-x7k2m 5005:5005
   ```

3. **Start proxy:**
   ```bash
   ./scripts/run-remote-proxy.sh --jdwp-host localhost --jdwp-port 5005
   ```

4. **Debug with Claude:**
   ```
   Start debug session.
   Set breakpoint in KafkaConsumer.onMessage() at line 89.
   Wait for message to arrive (may need to trigger test).
   When suspended, inspect message payload and processing state.
   ```

5. **Stop debugging:**
   ```bash
   Ctrl+C in port-forward and proxy terminals
   ```

---

## Next Steps

- **Read [debugger.md](debugger.md)** - Complete technical reference for all debugger tools
- **See [debugger-workflow.md](./debugger-workflow.md)** - MCP integration workflow patterns
- **Try [DebuggerWorkflowExample](../src/main/java/com/bitsapplied/descartes/example/debugger/README.md)** - Interactive examples
- **Check [claude.md](claude.md)** - Integration guide for projects using Descartes

---

## Support

- **GitHub Issues:** [https://github.com/your-repo/descartes-mcp/issues](https://github.com/your-repo/descartes-mcp/issues)
- **Documentation:** See `doc/` folder for comprehensive guides
- **Examples:** See `src/main/java/com/bitsapplied/descartes/example/` for working examples

---

**Last Updated:** 2025-01-07
