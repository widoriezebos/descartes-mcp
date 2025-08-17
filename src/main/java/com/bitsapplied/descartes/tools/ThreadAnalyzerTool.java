package com.bitsapplied.descartes.tools;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MCP tool for comprehensive thread analysis including deadlock detection, lock
 * analysis, and thread state monitoring.
 */
public class ThreadAnalyzerTool implements MCPTool {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

  public ThreadAnalyzerTool() {
    // Enable thread contention monitoring if available
    if (threadMXBean.isThreadContentionMonitoringSupported()) {
      threadMXBean.setThreadContentionMonitoringEnabled(true);
    }
    // Enable CPU time monitoring if available
    if (threadMXBean.isThreadCpuTimeSupported()) {
      threadMXBean.setThreadCpuTimeEnabled(true);
    }
  }

  @Override
  public String getToolName() {
    return "thread_analyzer";
  }

  @Override
  public String getToolDescription() {
    return "Advanced thread analysis and deadlock detection tool for JVM applications. "
        + "Monitors thread states (RUNNABLE, BLOCKED, WAITING), detects circular dependencies causing deadlocks, "
        + "analyzes lock contention and synchronization issues, tracks CPU time per thread, and identifies performance bottlenecks. "
        + "Essential for debugging concurrency issues, optimizing thread pool sizes, and ensuring application responsiveness. "
        + "Includes thread contention monitoring and CPU time tracking when supported by JVM.";
  }

  @Override
  public Map<String, Object> getToolSchema() {
    return Map.of("type", "object", "properties", Map.of("operation",
        Map.of("type", "string", "enum", List.of("threads", "deadlocks", "locks", "waiting", "blocked", "thread_dump"),
            "description", "The thread analysis operation to perform"),
        "thread_name",
        Map.of("type", "string", "description",
            "Filter by thread name pattern (for threads, waiting, blocked operations)"),
        "include_stack",
        Map.of("type", "boolean", "description", "Include stack traces in the output", "default", false),
        "max_stack_depth",
        Map.of("type", "integer", "description", "Maximum stack trace depth to include", "default", 10)), "required",
        List.of("operation"));
  }

  @Override
  public String executeTool(Map<String, Object> arguments) throws Exception {
    String operation = (String) arguments.get("operation");
    String threadNameFilter = (String) arguments.get("thread_name");
    Boolean includeStack = (Boolean) arguments.getOrDefault("include_stack", false);
    Integer maxStackDepth = ((Number) arguments.getOrDefault("max_stack_depth", 10)).intValue();

    if (operation == null) {
      throw new IllegalArgumentException("Operation is required");
    }

    Map<String, Object> result = switch (operation) {
    case "threads" -> getThreadStates(threadNameFilter, includeStack, maxStackDepth);
    case "deadlocks" -> detectDeadlocks(includeStack, maxStackDepth);
    case "locks" -> analyzeLocks(threadNameFilter, includeStack, maxStackDepth);
    case "waiting" -> getWaitingThreads(threadNameFilter, includeStack, maxStackDepth);
    case "blocked" -> getBlockedThreads(threadNameFilter, includeStack, maxStackDepth);
    case "thread_dump" -> getFullThreadDump(maxStackDepth);
    default -> throw new IllegalArgumentException("Unknown operation: " + operation);
    };

    return objectMapper.writeValueAsString(result);
  }

  /**
   * Get current thread states with detailed information.
   */
  private Map<String, Object> getThreadStates(String nameFilter, boolean includeStack, int maxStackDepth) {
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
    List<Map<String, Object>> threads = new ArrayList<>();

    // Count threads by state
    Map<String, Integer> stateCounts = new HashMap<>();

    for (ThreadInfo info : threadInfos) {
      if (info == null)
        continue;

      String threadName = info.getThreadName();
      if (nameFilter != null && !threadName.contains(nameFilter)) {
        continue;
      }

      Thread.State state = info.getThreadState();
      stateCounts.put(state.toString(), stateCounts.getOrDefault(state.toString(), 0) + 1);

      Map<String, Object> threadData = new HashMap<>();
      threadData.put("id", info.getThreadId());
      threadData.put("name", threadName);
      threadData.put("state", state.toString());
      threadData.put("priority", info.getPriority());
      threadData.put("daemon", info.isDaemon());

      // CPU and timing information
      if (threadMXBean.isThreadCpuTimeSupported()) {
        long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
        long userTime = threadMXBean.getThreadUserTime(info.getThreadId());
        threadData.put("cpu_time_ms", cpuTime / 1_000_000);
        threadData.put("user_time_ms", userTime / 1_000_000);
      }

      // Lock information
      LockInfo lockInfo = info.getLockInfo();
      if (lockInfo != null) {
        threadData.put("waiting_on_lock", Map.of("class_name", lockInfo.getClassName(), "identity_hash",
            Integer.toHexString(lockInfo.getIdentityHashCode())));
        threadData.put("lock_owner_id", info.getLockOwnerId());
        threadData.put("lock_owner_name", info.getLockOwnerName());
      }

      // Blocked time and count
      if (threadMXBean.isThreadContentionMonitoringSupported()) {
        threadData.put("blocked_count", info.getBlockedCount());
        threadData.put("blocked_time_ms", info.getBlockedTime());
        threadData.put("waited_count", info.getWaitedCount());
        threadData.put("waited_time_ms", info.getWaitedTime());
      }

      // Monitors (synchronized blocks)
      MonitorInfo[] monitors = info.getLockedMonitors();
      if (monitors.length > 0) {
        List<Map<String, Object>> monitorList = new ArrayList<>();
        for (MonitorInfo monitor : monitors) {
          monitorList.add(Map.of("class_name", monitor.getClassName(), "identity_hash",
              Integer.toHexString(monitor.getIdentityHashCode()), "stack_depth", monitor.getLockedStackDepth()));
        }
        threadData.put("locked_monitors", monitorList);
      }

      // Owned locks
      LockInfo[] synchronizers = info.getLockedSynchronizers();
      if (synchronizers.length > 0) {
        List<Map<String, Object>> syncList = new ArrayList<>();
        for (LockInfo sync : synchronizers) {
          syncList.add(Map.of("class_name", sync.getClassName(), "identity_hash",
              Integer.toHexString(sync.getIdentityHashCode())));
        }
        threadData.put("owned_synchronizers", syncList);
      }

      // Stack trace
      if (includeStack) {
        threadData.put("stack_trace", formatStackTrace(info.getStackTrace(), maxStackDepth));
      }

      threads.add(threadData);
    }

    return Map.of("status", "success", "thread_count", threads.size(), "state_summary", stateCounts, "threads",
        threads);
  }

  /**
   * Detect deadlocks in the system.
   */
  private Map<String, Object> detectDeadlocks(boolean includeStack, int maxStackDepth) {
    // findDeadlockedThreads() finds deadlocks for both monitors and ownable
    // synchronizers
    long[] deadlockedThreadIds = threadMXBean.findDeadlockedThreads();

    Set<Long> allDeadlocked = new HashSet<>();
    if (deadlockedThreadIds != null) {
      for (long id : deadlockedThreadIds) {
        allDeadlocked.add(id);
      }
    }

    if (allDeadlocked.isEmpty()) {
      return Map.of("status", "success", "deadlocks_found", false, "message", "No deadlocks detected");
    }

    // Get detailed information about deadlocked threads
    ThreadInfo[] deadlockedThreads = threadMXBean
        .getThreadInfo(allDeadlocked.stream().mapToLong(Long::longValue).toArray(), includeStack ? maxStackDepth : 0);

    List<Map<String, Object>> deadlockChains = analyzeDeadlockChains(deadlockedThreads, includeStack, maxStackDepth);

    return Map.of("status", "success", "deadlocks_found", true, "deadlocked_thread_count", allDeadlocked.size(),
        "deadlock_chains", deadlockChains, "deadlocked_thread_ids", allDeadlocked);
  }

  /**
   * Analyze deadlock chains to show circular dependencies.
   */
  private List<Map<String, Object>> analyzeDeadlockChains(ThreadInfo[] deadlockedThreads, boolean includeStack,
      int maxStackDepth) {
    List<Map<String, Object>> chains = new ArrayList<>();
    Set<Long> processed = new HashSet<>();

    for (ThreadInfo thread : deadlockedThreads) {
      if (thread == null || processed.contains(thread.getThreadId())) {
        continue;
      }

      List<Map<String, Object>> chain = new ArrayList<>();
      ThreadInfo current = thread;
      Set<Long> chainIds = new HashSet<>();

      while (current != null && !chainIds.contains(current.getThreadId())) {
        chainIds.add(current.getThreadId());
        processed.add(current.getThreadId());

        Map<String, Object> threadInfo = new HashMap<>();
        threadInfo.put("thread_id", current.getThreadId());
        threadInfo.put("thread_name", current.getThreadName());
        threadInfo.put("thread_state", current.getThreadState().toString());

        LockInfo lockInfo = current.getLockInfo();
        if (lockInfo != null) {
          threadInfo.put("waiting_on", Map.of("class_name", lockInfo.getClassName(), "identity_hash",
              Integer.toHexString(lockInfo.getIdentityHashCode())));
          threadInfo.put("waiting_for_thread_id", current.getLockOwnerId());
          threadInfo.put("waiting_for_thread_name", current.getLockOwnerName());
        }

        if (includeStack) {
          threadInfo.put("stack_trace", formatStackTrace(current.getStackTrace(), maxStackDepth));
        }

        chain.add(threadInfo);

        // Move to the thread that owns the lock we're waiting for
        if (current.getLockOwnerId() > 0) {
          current = threadMXBean.getThreadInfo(current.getLockOwnerId());
        } else {
          break;
        }
      }

      if (!chain.isEmpty()) {
        chains.add(Map.of("chain_length", chain.size(), "is_circular",
            chainIds.contains(current != null ? current.getThreadId() : -1), "threads", chain));
      }
    }

    return chains;
  }

  /**
   * Analyze locks currently held in the system.
   */
  @SuppressWarnings("unused")
  private Map<String, Object> analyzeLocks(String threadNameFilter, boolean includeStack, int maxStackDepth) {
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

    List<Map<String, Object>> lockHolders = new ArrayList<>();
    Map<String, List<String>> lockToThreads = new HashMap<>();

    for (ThreadInfo info : threadInfos) {
      if (info == null)
        continue;

      String threadName = info.getThreadName();
      if (threadNameFilter != null && !threadName.contains(threadNameFilter)) {
        continue;
      }

      // Get monitors (synchronized blocks)
      MonitorInfo[] monitors = info.getLockedMonitors();

      // Get owned synchronizers (ReentrantLocks, etc.)
      LockInfo[] synchronizers = info.getLockedSynchronizers();

      if (monitors.length > 0 || synchronizers.length > 0) {
        Map<String, Object> holder = new HashMap<>();
        holder.put("thread_id", info.getThreadId());
        holder.put("thread_name", threadName);
        holder.put("thread_state", info.getThreadState().toString());

        // Process monitors
        if (monitors.length > 0) {
          List<Map<String, Object>> monitorList = new ArrayList<>();
          for (MonitorInfo monitor : monitors) {
            String lockId = monitor.getClassName() + "@" + Integer.toHexString(monitor.getIdentityHashCode());

            monitorList.add(Map.of("type", "monitor", "class_name", monitor.getClassName(), "identity_hash",
                Integer.toHexString(monitor.getIdentityHashCode()), "lock_id", lockId, "stack_depth",
                monitor.getLockedStackDepth(), "stack_frame",
                monitor.getLockedStackFrame() != null ? monitor.getLockedStackFrame().toString() : "unknown"));

            lockToThreads.computeIfAbsent(lockId, __ -> new ArrayList<>()).add(threadName);
          }
          holder.put("monitors", monitorList);
        }

        // Process synchronizers
        if (synchronizers.length > 0) {
          List<Map<String, Object>> syncList = new ArrayList<>();
          for (LockInfo sync : synchronizers) {
            String lockId = sync.getClassName() + "@" + Integer.toHexString(sync.getIdentityHashCode());

            syncList.add(Map.of("type", "synchronizer", "class_name", sync.getClassName(), "identity_hash",
                Integer.toHexString(sync.getIdentityHashCode()), "lock_id", lockId));

            lockToThreads.computeIfAbsent(lockId, __ -> new ArrayList<>()).add(threadName);
          }
          holder.put("synchronizers", syncList);
        }

        if (includeStack) {
          holder.put("stack_trace", formatStackTrace(info.getStackTrace(), maxStackDepth));
        }

        lockHolders.add(holder);
      }
    }

    // Find contested locks (held by one thread, wanted by others)
    List<Map<String, Object>> contestedLocks = findContestedLocks(threadInfos);

    return Map.of("status", "success", "lock_holders_count", lockHolders.size(), "unique_locks_count",
        lockToThreads.size(), "lock_holders", lockHolders, "lock_to_threads", lockToThreads, "contested_locks",
        contestedLocks);
  }

  /**
   * Find locks that are contested (wanted by waiting threads).
   */
  @SuppressWarnings("unused")
  private List<Map<String, Object>> findContestedLocks(ThreadInfo[] threadInfos) {
    Map<String, Map<String, Object>> contestedLocks = new HashMap<>();

    for (ThreadInfo info : threadInfos) {
      if (info == null)
        continue;

      LockInfo lockInfo = info.getLockInfo();
      if (lockInfo != null && info.getLockOwnerId() > 0) {
        String lockId = lockInfo.getClassName() + "@" + Integer.toHexString(lockInfo.getIdentityHashCode());

        Map<String, Object> lockData = contestedLocks.computeIfAbsent(lockId, __ -> new HashMap<>());
        lockData.put("lock_class", lockInfo.getClassName());
        lockData.put("lock_hash", Integer.toHexString(lockInfo.getIdentityHashCode()));
        lockData.put("owner_thread_id", info.getLockOwnerId());
        lockData.put("owner_thread_name", info.getLockOwnerName());

        @SuppressWarnings("unchecked")
        List<String> waiters = (List<String>) lockData.computeIfAbsent("waiting_threads", __ -> new ArrayList<>());
        waiters.add(info.getThreadName());
      }
    }

    return contestedLocks.values().stream().map(lock -> {
      @SuppressWarnings("unchecked")
      List<String> waiters = (List<String>) lock.get("waiting_threads");
      lock.put("waiter_count", waiters.size());
      return lock;
    }).collect(Collectors.toList());
  }

  /**
   * Get threads that are waiting (WAITING or TIMED_WAITING state).
   */
  private Map<String, Object> getWaitingThreads(String nameFilter, boolean includeStack, int maxStackDepth) {
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
    List<Map<String, Object>> waitingThreads = new ArrayList<>();

    for (ThreadInfo info : threadInfos) {
      if (info == null)
        continue;

      Thread.State state = info.getThreadState();
      if (state != Thread.State.WAITING && state != Thread.State.TIMED_WAITING) {
        continue;
      }

      String threadName = info.getThreadName();
      if (nameFilter != null && !threadName.contains(nameFilter)) {
        continue;
      }

      Map<String, Object> threadData = new HashMap<>();
      threadData.put("thread_id", info.getThreadId());
      threadData.put("thread_name", threadName);
      threadData.put("state", state.toString());
      threadData.put("is_timed_wait", state == Thread.State.TIMED_WAITING);

      // What is it waiting on?
      LockInfo lockInfo = info.getLockInfo();
      if (lockInfo != null) {
        threadData.put("waiting_on", Map.of("class_name", lockInfo.getClassName(), "identity_hash",
            Integer.toHexString(lockInfo.getIdentityHashCode())));

        if (info.getLockOwnerId() > 0) {
          threadData.put("lock_owner_id", info.getLockOwnerId());
          threadData.put("lock_owner_name", info.getLockOwnerName());
        }
      }

      // Get the wait reason from stack trace
      StackTraceElement[] stack = info.getStackTrace();
      if (stack.length > 0) {
        StackTraceElement topFrame = stack[0];
        String waitReason = determineWaitReason(topFrame);
        threadData.put("wait_reason", waitReason);
        threadData.put("wait_location", topFrame.toString());
      }

      // Timing information
      if (threadMXBean.isThreadContentionMonitoringSupported()) {
        threadData.put("waited_count", info.getWaitedCount());
        threadData.put("waited_time_ms", info.getWaitedTime());
      }

      if (includeStack) {
        threadData.put("stack_trace", formatStackTrace(stack, maxStackDepth));
      }

      waitingThreads.add(threadData);
    }

    // Group by wait reason
    Map<String, Long> waitReasonCounts = waitingThreads.stream()
        .collect(Collectors.groupingBy(t -> (String) t.getOrDefault("wait_reason", "unknown"), Collectors.counting()));

    return Map.of("status", "success", "waiting_thread_count", waitingThreads.size(), "wait_reason_summary",
        waitReasonCounts, "waiting_threads", waitingThreads);
  }

  /**
   * Get threads that are blocked (BLOCKED state).
   */
  @SuppressWarnings("unused")
  private Map<String, Object> getBlockedThreads(String nameFilter, boolean includeStack, int maxStackDepth) {
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
    List<Map<String, Object>> blockedThreads = new ArrayList<>();

    for (ThreadInfo info : threadInfos) {
      if (info == null)
        continue;

      if (info.getThreadState() != Thread.State.BLOCKED) {
        continue;
      }

      String threadName = info.getThreadName();
      if (nameFilter != null && !threadName.contains(nameFilter)) {
        continue;
      }

      Map<String, Object> threadData = new HashMap<>();
      threadData.put("thread_id", info.getThreadId());
      threadData.put("thread_name", threadName);
      threadData.put("state", "BLOCKED");

      // What is blocking it?
      LockInfo lockInfo = info.getLockInfo();
      if (lockInfo != null) {
        threadData.put("blocked_on", Map.of("class_name", lockInfo.getClassName(), "identity_hash",
            Integer.toHexString(lockInfo.getIdentityHashCode())));
        threadData.put("lock_owner_id", info.getLockOwnerId());
        threadData.put("lock_owner_name", info.getLockOwnerName());
      }

      // Blocking statistics
      if (threadMXBean.isThreadContentionMonitoringSupported()) {
        threadData.put("blocked_count", info.getBlockedCount());
        threadData.put("blocked_time_ms", info.getBlockedTime());
      }

      if (includeStack) {
        threadData.put("stack_trace", formatStackTrace(info.getStackTrace(), maxStackDepth));
      }

      blockedThreads.add(threadData);
    }

    // Group blocked threads by what they're blocked on
    Map<String, List<String>> blockingLocks = new HashMap<>();
    for (Map<String, Object> thread : blockedThreads) {
      @SuppressWarnings("unchecked")
      Map<String, Object> blockedOn = (Map<String, Object>) thread.get("blocked_on");
      if (blockedOn != null) {
        String lockId = blockedOn.get("class_name") + "@" + blockedOn.get("identity_hash");
        blockingLocks.computeIfAbsent(lockId, __ -> new ArrayList<>()).add((String) thread.get("thread_name"));
      }
    }

    return Map.of("status", "success", "blocked_thread_count", blockedThreads.size(), "blocking_locks", blockingLocks,
        "blocked_threads", blockedThreads);
  }

  /**
   * Get a full thread dump.
   */
  private Map<String, Object> getFullThreadDump(int maxStackDepth) {
    ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);

    StringBuilder dump = new StringBuilder();
    dump.append(String.format("Full thread dump %s (%s %s):\n\n", System.getProperty("java.vm.name"),
        System.getProperty("java.vm.version"), System.getProperty("java.vm.info")));

    for (ThreadInfo info : threadInfos) {
      if (info == null)
        continue;
      dump.append(formatThreadInfo(info, maxStackDepth));
      dump.append("\n");
    }

    // Add deadlock information
    long[] deadlocked = threadMXBean.findDeadlockedThreads();
    if (deadlocked != null && deadlocked.length > 0) {
      dump.append("\n===== DEADLOCK DETECTED =====\n");
      ThreadInfo[] deadlockedThreads = threadMXBean.getThreadInfo(deadlocked, maxStackDepth);
      for (ThreadInfo info : deadlockedThreads) {
        if (info != null) {
          dump.append(formatThreadInfo(info, maxStackDepth));
          dump.append("\n");
        }
      }
    }

    return Map.of("status", "success", "thread_count", threadInfos.length, "thread_dump", dump.toString(), "timestamp",
        System.currentTimeMillis());
  }

  /**
   * Format thread information for text output.
   */
  private String formatThreadInfo(ThreadInfo info, int maxStackDepth) {
    StringBuilder sb = new StringBuilder();

    sb.append(String.format("\"%s\" #%d %s prio=%d tid=0x%x state=%s", info.getThreadName(), info.getThreadId(),
        info.isDaemon() ? "daemon" : "", info.getPriority(), info.getThreadId(), info.getThreadState()));

    LockInfo lockInfo = info.getLockInfo();
    if (lockInfo != null) {
      sb.append(String.format("\n   waiting on %s@%s", lockInfo.getClassName(),
          Integer.toHexString(lockInfo.getIdentityHashCode())));

      if (info.getLockOwnerName() != null) {
        sb.append(String.format(" owned by \"%s\" id=%d", info.getLockOwnerName(), info.getLockOwnerId()));
      }
    }

    sb.append("\n");

    // Stack trace
    StackTraceElement[] stack = info.getStackTrace();
    for (int i = 0; i < Math.min(stack.length, maxStackDepth); i++) {
      sb.append("\tat ").append(stack[i]).append("\n");

      // Show monitors at this stack depth
      for (MonitorInfo monitor : info.getLockedMonitors()) {
        if (monitor.getLockedStackDepth() == i) {
          sb.append(String.format("\t- locked %s@%s\n", monitor.getClassName(),
              Integer.toHexString(monitor.getIdentityHashCode())));
        }
      }
    }

    if (stack.length > maxStackDepth) {
      sb.append(String.format("\t... %d more\n", stack.length - maxStackDepth));
    }

    // Locked synchronizers
    LockInfo[] synchronizers = info.getLockedSynchronizers();
    if (synchronizers.length > 0) {
      sb.append("\n   Locked synchronizers:\n");
      for (LockInfo sync : synchronizers) {
        sb.append(String.format("\t- %s@%s\n", sync.getClassName(), Integer.toHexString(sync.getIdentityHashCode())));
      }
    }

    return sb.toString();
  }

  /**
   * Format stack trace for JSON output.
   */
  private List<String> formatStackTrace(StackTraceElement[] stack, int maxDepth) {
    List<String> formatted = new ArrayList<>();
    for (int i = 0; i < Math.min(stack.length, maxDepth); i++) {
      formatted.add(stack[i].toString());
    }
    if (stack.length > maxDepth) {
      formatted.add(String.format("... %d more", stack.length - maxDepth));
    }
    return formatted;
  }

  /**
   * Determine the wait reason from the top stack frame.
   */
  private String determineWaitReason(StackTraceElement frame) {
    String method = frame.getMethodName();
    String className = frame.getClassName();

    if (method.equals("park"))
      return "LockSupport.park";
    if (method.equals("wait"))
      return "Object.wait";
    if (method.equals("sleep"))
      return "Thread.sleep";
    if (method.equals("join"))
      return "Thread.join";
    if (className.contains("BlockingQueue"))
      return "BlockingQueue operation";
    if (className.contains("Selector"))
      return "NIO Selector";
    if (className.contains("Socket"))
      return "Socket I/O";
    if (className.contains("Condition"))
      return "Condition.await";
    if (className.contains("CountDownLatch"))
      return "CountDownLatch.await";
    if (className.contains("CyclicBarrier"))
      return "CyclicBarrier.await";
    if (className.contains("Semaphore"))
      return "Semaphore.acquire";
    if (className.contains("Exchanger"))
      return "Exchanger.exchange";

    return method + " at " + className;
  }
}