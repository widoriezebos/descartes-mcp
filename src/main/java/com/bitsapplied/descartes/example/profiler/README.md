# Descartes Profiler Workflow Example

This comprehensive example demonstrates the complete Descartes profiling workflow, showcasing how to use JFR-based performance profiling to identify and analyze performance bottlenecks in Java applications.

## 🎯 What This Example Demonstrates

### Core Profiling Capabilities

1. **CPU Profiling** - Identify computation hotspots
   - Recursive algorithms showing deep call stacks
   - Hot loops consuming CPU time
   - Method call hierarchies and time distribution

2. **Allocation Profiling** - Find memory allocation patterns
   - Excessive object creation
   - Collection resizing overhead
   - String concatenation anti-patterns
   - Memory leak detection

3. **Comprehensive Profiling** - Complete performance picture
   - CPU + Memory + Locks + I/O + GC events
   - Multi-dimensional performance analysis
   - Real-world mixed workload scenarios

4. **Flame Graph Generation** - Visual performance analysis
   - Interactive HTML flame graphs
   - Zoom, search, and hover for details
   - Color-coded by package/class
   - Self-contained single-file output

### Realistic Workload Generators

The example includes four workload generators that simulate real application behavior:

- **ComputationWorkload** - CPU-intensive operations (Fibonacci, primes, matrix math, crypto)
- **AllocationWorkload** - Memory allocation patterns (collections, strings, serialization)
- **ConcurrencyWorkload** - Lock contention (synchronized methods, concurrent maps, producer-consumer)
- **IOWorkload** - I/O operations (buffered/unbuffered, NIO, compression)

## 📋 Requirements

- **JDK 11+** (JFR support required)
- **Maven** for building and running
- **Port 9080** available for MCP server
- **~500MB disk space** for profile storage
- **Modern web browser** for viewing flame graphs

## 🚀 Quick Start

### Automated Demo Mode (Recommended First Run)

Run the complete automated demo that walks through all profiling scenarios:

```bash
# From project root
mvn compile exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample"
```

This will:
1. ✅ Start MCP server on port 9080
2. ✅ Run CPU profiling demo (15 seconds)
3. ✅ Run allocation profiling demo (20 seconds)
4. ✅ Run comprehensive profiling demo (30 seconds)
5. ✅ Show profile comparison and summary
6. ✅ Save profiles to `./profiler-demo-output/`

**Duration:** ~3 minutes total

### Interactive Mode

For hands-on experimentation with MCP tools:

```bash
mvn compile exec:java \
  -Dexec.mainClass="com.bitsapplied.descartes.example.profiler.ProfilerWorkflowExample" \
  -Dexec.args="--interactive"
```

This will:
- Start the MCP server with all profiler tools registered
- Start background workloads (computation, allocation, concurrency, I/O)
- Keep server running for manual MCP client interaction
- Print instructions for using profiler tools

**To connect:** Use Claude Desktop with the MCP adapter (see `config/mcp/` in project root)

Press Enter to stop the server and exit.

## 📊 Understanding the Output

### Console Output

The example provides detailed explanatory output:

```
🚀 Starting Automated Profiler Demo
================================================================================

📊 DEMO 1: CPU Profiling
--------------------------------------------------------------------------------

CPU profiling identifies computation hotspots - methods consuming
the most CPU time. Useful for finding performance bottlenecks.

Profile type: CPU (sampling every 10ms, ~1% overhead)
Duration: 15 seconds
Workload: Recursive Fibonacci, prime generation, matrix math

🔄 Starting CPU-intensive workload...
▶️  Starting CPU profile (15s)...
   Profile ID: dd-MM-yyyy_HH.mm.ss-profile-abc123

   Profiling: 1s / 15s
   Profiling: 2s / 15s
   ...
```

### Profile Output Directory

All profiles are saved to `./profiler-demo-output/` with timestamped IDs:

```
profiler-demo-output/
├── 03-11-2025_14.30.15-profile-abc123.jfr
├── 03-11-2025_14.31.00-profile-def456.jfr
└── 03-11-2025_14.32.15-profile-ghi789.jfr
```

### Expected Hotspots

#### CPU Profile Hotspots
The CPU profile should show these methods as top consumers:

1. **ComputationWorkload.recursiveFibonacci()** (30-40%)
   - Deep call stack (tall in flame graph)
   - Exponential time complexity visible

2. **ComputationWorkload.isPrime()** (20-25%)
   - Hot loop (wide in flame graph)
   - High self-time percentage

3. **ComputationWorkload.multiplyCell()** (15-20%)
   - Nested loop structure
   - Matrix multiplication overhead

4. **MessageDigest.digest()** (10-15%)
   - Native method (crypto operations)
   - Shows JVM/system library interaction

#### Allocation Profile Hotspots

1. **AllocationWorkload.stringConcatenationAntipattern()** (40-50% of allocations)
   - Many intermediate String objects
   - O(n²) allocation behavior

2. **AllocationWorkload.createLargeObjects()** (25-30%)
   - Large byte[] allocations
   - High allocation rate visible

3. **AllocationWorkload.collectionChurning()** (15-20%)
   - ArrayList internal array resizing
   - HashMap Entry object allocations

4. **AllocationWorkload.streamApiOperations()** (10-15%)
   - Stream API overhead
   - Functional programming allocations

#### Comprehensive Profile

Shows all dimensions:
- **CPU** - Computation hotspots as above
- **Allocation** - Memory patterns as above
- **Locks** - ConcurrencyWorkload.contentedSynchronizedMethod() wait times
- **I/O** - IOWorkload file operation wait times
- **GC** - Garbage collection pauses

## 🔥 Flame Graph Interpretation

### What Flame Graphs Show

Flame graphs are a visual representation of profiling data:

```
┌────────────────────────────────────────┐ ← Top: Leaf methods (actual work)
│  isPrime  │ multiplyCell │ digest      │
├───────────────────┬────────────────────┤
│  generatePrimes   │ matrixMultiply     │
├───────────────────────────────────────┤
│          recursiveFibonacci            │
├───────────────────────────────────────┤
│            startContinuousLoad         │
├───────────────────────────────────────┤
│                  main                  │ ← Bottom: Entry points
└────────────────────────────────────────┘
```

### Reading the Graph

**Width:**
- Represents time spent (CPU) or bytes allocated (allocation profiles)
- Wider = more expensive
- Look for unexpectedly wide sections

**Height:**
- Call stack depth
- Bottom = entry points (main, thread starts)
- Top = leaf methods (where work happens)
- Tall stacks = deep recursion

**Colors:**
- Hash-based coloring by package/class
- Same color = same package
- Helps identify which parts of codebase are expensive

### Interactive Features

When you open the HTML flame graph in a browser:

1. **Click** - Zoom into a specific call path
2. **Search** (Ctrl+F or search box) - Highlight methods matching pattern
3. **Hover** - See detailed statistics (method name, time, percentage)
4. **Reset** - Click "Reset Zoom" to return to full view

### Analysis Workflow

1. **Find the widest sections** - These consume the most time/memory
2. **Check if they're expected** - Is this method supposed to be expensive?
3. **Look at call hierarchy** - What's calling this expensive method?
4. **Identify optimization opportunities** - Can this be cached, parallelized, or eliminated?

Example:
```
If stringConcatenationAntipattern() is 40% of allocations:
→ Look at the implementation
→ See it's using + operator in loop
→ Optimization: Use StringBuilder
→ Re-profile to verify improvement
```

## 🔧 Using MCP Tools (Interactive Mode)

### Complete Profiling Workflow

#### 1. Start Profiling

```python
# Claude Desktop / MCP Client
profiler_start(duration=30, profile_type="cpu")
```

**Parameters:**
- `duration` - Recording duration in seconds (10-300)
- `profile_type` - One of: "cpu", "allocation", "comprehensive", "lightweight"

**Returns:** Profile ID for later reference

#### 2. Wait for Completion

Profiling runs asynchronously and auto-stops after duration.

Monitor progress:
```python
profiler_list()  # Check if recording is complete
```

#### 3. Analyze Hotspots

```python
profiler_hotspots(
    profile_id="03-11-2025_14.30.15-profile-abc123",
    limit=10
)
```

**Output:**
```
Top 10 CPU Hotspots:
1. ComputationWorkload.recursiveFibonacci() - 35.2% (self: 2.1%, total: 35.2%)
2. ComputationWorkload.isPrime() - 23.7% (self: 23.7%, total: 23.7%)
3. ComputationWorkload.multiplyCell() - 18.5% (self: 18.5%, total: 18.5%)
...
```

**Understanding percentages:**
- `self` - Time spent in this method itself (excluding calls)
- `total` - Time spent including all methods it calls

#### 4. Examine Call Trees

For the top hotspot, see what it calls:

```python
profiler_call_tree(
    profile_id="03-11-2025_14.30.15-profile-abc123",
    method="ComputationWorkload.recursiveFibonacci",
    max_depth=5
)
```

**Output:**
```
Call Tree for: ComputationWorkload.recursiveFibonacci
└─ recursiveFibonacci (35.2%)
   ├─ recursiveFibonacci (17.1%)  [recursive call]
   │  ├─ recursiveFibonacci (8.4%)
   │  │  ├─ recursiveFibonacci (4.1%)
   │  │  └─ recursiveFibonacci (4.0%)
   │  └─ recursiveFibonacci (8.2%)
   └─ recursiveFibonacci (16.8%)  [recursive call]
```

#### 5. Export Flame Graph

```python
profiler_export(
    profile_id="03-11-2025_14.30.15-profile-abc123",
    format="flamegraph",
    output="./my-cpu-profile.html"
)
```

**Output:** Self-contained HTML file with interactive flame graph

#### 6. Open in Browser

```bash
# macOS
open ./my-cpu-profile.html

# Linux
xdg-open ./my-cpu-profile.html

# Windows
start ./my-cpu-profile.html
```

### Profile Type Selection Guide

| Profile Type  | Overhead | Events Captured           | Use Case                          |
|---------------|----------|---------------------------|-----------------------------------|
| `lightweight` | ~0.5%    | CPU sampling (20ms)       | Production monitoring             |
| `cpu`         | ~1%      | CPU sampling (10ms)       | Finding computation bottlenecks   |
| `allocation`  | ~1.5%    | Memory allocations        | Memory leak investigation         |
| `comprehensive` | ~2%    | CPU+Alloc+Locks+I/O+GC    | Deep investigation (staging)      |

**Recommendation:**
- Start with `cpu` for most performance issues
- Use `allocation` if you suspect memory problems
- Use `comprehensive` for complex issues or when you don't know where to start
- Use `lightweight` in production for continuous monitoring

## 🎓 Educational Value

### Performance Anti-Patterns Demonstrated

This example includes intentional anti-patterns to teach profiling:

#### 1. String Concatenation in Loop
```java
// BAD - O(n²) allocations
String result = "";
for (int i = 0; i < 1000; i++) {
    result += "iteration_" + i + ";";  // Creates new String each time!
}

// GOOD - O(n) allocations
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 1000; i++) {
    sb.append("iteration_").append(i).append(";");
}
```

**Profiler shows:** stringConcatenationAntipattern() as allocation hotspot

#### 2. Collection Without Initial Capacity
```java
// BAD - multiple array reallocations
List<Object> list = new ArrayList<>();
for (int i = 0; i < 10000; i++) {
    list.add(new Object());  // Causes resizing 14 times
}

// GOOD - single allocation
List<Object> list = new ArrayList<>(10000);
for (int i = 0; i < 10000; i++) {
    list.add(new Object());
}
```

**Profiler shows:** Internal array allocation in collectionChurning()

#### 3. Long Critical Section
```java
// BAD - holding lock during slow operation
synchronized void process() {
    // ... fast work ...
    Thread.sleep(100);  // Slow operation under lock!
    // ... more work ...
}

// GOOD - minimize synchronized section
void process() {
    // ... fast work without lock ...
    synchronized(lock) {
        // Only critical section here
    }
    // ... more work without lock ...
}
```

**Profiler shows:** High lock contention and wait time

#### 4. Unbuffered I/O
```java
// BAD - system call for each character
FileWriter writer = new FileWriter(file);
for (char c : data.toCharArray()) {
    writer.write(c);  // Extremely slow!
}

// GOOD - buffered writes
BufferedWriter writer = new BufferedWriter(new FileWriter(file));
writer.write(data);  // Much faster
```

**Profiler shows:** High I/O wait time in comprehensive profile

### Learning Objectives

After running this example, you should understand:

✅ How to start profiling sessions with appropriate profile types
✅ How to interpret hotspot percentages (self vs total time)
✅ How to read flame graphs and identify performance issues
✅ How to distinguish between CPU, memory, lock, and I/O bottlenecks
✅ Common performance anti-patterns and their profiling signatures
✅ How to export and share profiling results
✅ When to use different profile types based on overhead and goals

## 🔍 Troubleshooting

### "JDK 11+ required for JFR support"

**Problem:** Running on JDK 8 or earlier
**Solution:** Upgrade to JDK 11 or later

```bash
java -version  # Check current version
```

### "Port 9080 already in use"

**Problem:** Another application is using port 9080
**Solution:** Kill the other process or change the port

```bash
# Find process using port 9080
lsof -i :9080

# Kill it
kill -9 <PID>

# Or change the port in ProfilerWorkflowExample.java
```

### "No profiles found" after profiling

**Problem:** Profile didn't complete or storage path issue
**Solution:** Check that profiling duration completed and verify storage directory

```bash
# Check if profile files exist
ls -lh ./profiler-demo-output/
```

### Flame graph HTML won't open

**Problem:** Browser security restrictions or file permission issues
**Solution:**
1. Try different browser (Chrome, Firefox)
2. Check file permissions
3. Move HTML file to a location with proper permissions

### High memory usage during profiling

**Problem:** Comprehensive profile on large application
**Solution:**
- Use more specific profile types (cpu, allocation)
- Reduce profiling duration
- Increase JVM heap size: `-Xmx2g`

## 📚 Further Reading

### Descartes Documentation
- **CLAUDE.md** - Complete profiler documentation
- **doc/tools.md** - Profiler tool API reference
- **SimpleMCPServerExample.java** - Basic MCP server setup

### JFR Resources
- [JDK Flight Recorder Guide](https://docs.oracle.com/javacomponents/jmc-5-4/jfr-runtime-guide/about.htm)
- [JFR Events Reference](https://sap.github.io/SapMachine/jfrevents/)

### Flame Graph Resources
- [Flame Graphs (Brendan Gregg)](http://www.brendangregg.com/flamegraphs.html)
- [Java Flame Graphs Introduction](https://netflixtechblog.com/java-in-flames-e763b3d32166)

## 🤝 Contributing

Found an issue or have a suggestion? Please report it at:
https://github.com/widoriezebos/descartes-mcp/issues

## 📄 License

This example is part of the Descartes MCP project.
See LICENSE in project root for details.

---

**Happy Profiling! 🔥**

For questions or support, refer to the main project documentation or create an issue on GitHub.
