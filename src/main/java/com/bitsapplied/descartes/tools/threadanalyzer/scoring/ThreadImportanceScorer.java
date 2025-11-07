package com.bitsapplied.descartes.tools.threadanalyzer.scoring;

import java.lang.management.ThreadInfo;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores threads based on their debugging importance/value.
 *
 * <p>The scoring algorithm prioritizes threads that are most likely to be relevant
 * during debugging and troubleshooting:
 * <ul>
 *   <li>BLOCKED threads (potential deadlocks/contention) score highest</li>
 *   <li>Threads with high CPU time or contention metrics score high</li>
 *   <li>Non-daemon application threads are prioritized over background threads</li>
 *   <li>JVM system threads (GC, Reference Handler, etc.) score lowest</li>
 * </ul>
 *
 * <p><b>Scoring Breakdown:</b>
 * <pre>
 * Thread State:
 *   BLOCKED                → +100  (deadlock/contention indicator)
 *   WAITING/TIMED_WAITING  → +50   (potentially stuck)
 *   RUNNABLE               → +25   (active work)
 *   NEW/TERMINATED         → +0    (not interesting)
 *
 * Activity Metrics:
 *   CPU time > 0ms         → +50
 *   CPU time > 1000ms      → +75   (heavy computation)
 *   Blocked time > 0ms     → +60   (contention victim)
 *   Blocked time > 1000ms  → +80   (serious contention)
 *
 * Thread Characteristics:
 *   Non-daemon             → +30   (application threads)
 *   Priority > 5           → +10   (higher priority)
 *
 * Penalties:
 *   Well-known JVM threads → -100  (rarely debugging-relevant)
 *   Examples: "Reference Handler", "Finalizer", "Signal Dispatcher",
 *            "Attach Listener", "Common-Cleaner", GC threads
 *
 * Bonuses:
 *   Application package    → +40   (app code vs library code)
 *   Thread pool pattern    → +20   (managed concurrency)
 *   Early thread ID        → +10   (core threads)
 * </pre>
 *
 * <p><b>Result Ranges:</b>
 * <ul>
 *   <li>High-value threads (BLOCKED with activity): 150-250</li>
 *   <li>Medium-value threads (RUNNABLE, non-daemon): 50-100</li>
 *   <li>Low-value threads (idle daemons): 0-25</li>
 *   <li>System threads (JVM internals): -100 to 0</li>
 * </ul>
 *
 * @since 0.0.1
 */
public class ThreadImportanceScorer {

    // Well-known JVM system thread names that are rarely useful for debugging
    private static final Set<String> JVM_SYSTEM_THREADS = Set.of(
        "Reference Handler",
        "Finalizer",
        "Signal Dispatcher",
        "Attach Listener",
        "Common-Cleaner",
        "Notification Thread",
        "DestroyJavaVM"
    );

    // Patterns for recognizing JVM-internal threads
    private static final Pattern GC_THREAD_PATTERN = Pattern.compile(
        "^(GC |G1 |ZGC |Shenandoah |VM |C[12] CompilerThread|Service Thread).*",
        Pattern.CASE_INSENSITIVE
    );

    // Patterns for recognizing thread pools
    private static final Pattern THREAD_POOL_PATTERN = Pattern.compile(
        ".*(-pool-|-executor-|-worker-|-thread-).*",
        Pattern.CASE_INSENSITIVE
    );

    // CPU time thresholds (in milliseconds)
    private static final long HIGH_CPU_THRESHOLD_MS = 1000;

    // Blocked time thresholds (in milliseconds)
    private static final long HIGH_BLOCKED_THRESHOLD_MS = 1000;

    // Thread ID threshold for "early creation" bonus
    private static final long EARLY_THREAD_ID_THRESHOLD = 100;

    // Application package patterns for bonus (configurable)
    private final Set<String> appPackagePatterns;

    /**
     * Creates a scorer with default configuration (no app-specific package bonuses).
     */
    public ThreadImportanceScorer() {
        this(Set.of());
    }

    /**
     * Creates a scorer with application-specific package patterns for bonus scoring.
     *
     * @param appPackagePatterns Set of package prefixes that identify application code
     *                          (e.g., "com.mycompany", "com.bitsapplied")
     */
    public ThreadImportanceScorer(Set<String> appPackagePatterns) {
        this.appPackagePatterns = Set.copyOf(appPackagePatterns);
    }

    /**
     * Calculates the importance score for a thread.
     *
     * @param threadInfo Thread to score
     * @param cpuTimeMs CPU time in milliseconds (use -1 if unavailable)
     * @return Importance score (higher = more important for debugging)
     */
    public int scoreThread(ThreadInfo threadInfo, long cpuTimeMs) {
        if (threadInfo == null) {
            return 0;
        }

        int score = 0;

        // State-based scoring (most important factor)
        score += scoreState(threadInfo.getThreadState());

        // Activity metrics
        score += scoreCpuTime(cpuTimeMs);
        score += scoreBlockedTime(threadInfo.getBlockedTime());

        // Thread characteristics
        score += scoreThreadCharacteristics(threadInfo);

        // Well-known thread penalties/bonuses
        score += scoreThreadName(threadInfo.getThreadName(), threadInfo.getThreadId());

        // Application-specific bonuses
        score += scoreStackTrace(threadInfo);

        return score;
    }

    /**
     * Scores based on thread state.
     */
    private int scoreState(Thread.State state) {
        return switch (state) {
            case BLOCKED -> 100;  // Highest priority: potential deadlock/contention
            case WAITING, TIMED_WAITING -> 50;  // Medium priority: might be stuck
            case RUNNABLE -> 25;  // Active work happening
            case NEW, TERMINATED -> 0;  // Not interesting
        };
    }

    /**
     * Scores based on CPU time.
     */
    private int scoreCpuTime(long cpuTimeMs) {
        if (cpuTimeMs < 0) {
            return 0;  // CPU time not available
        }
        if (cpuTimeMs == 0) {
            return 0;  // No CPU activity
        }
        if (cpuTimeMs > HIGH_CPU_THRESHOLD_MS) {
            return 75;  // Heavy computation
        }
        return 50;  // Some activity
    }

    /**
     * Scores based on blocked time.
     */
    private int scoreBlockedTime(long blockedTimeMs) {
        if (blockedTimeMs < 0) {
            return 0;  // Blocked time not available
        }
        if (blockedTimeMs == 0) {
            return 0;  // No contention
        }
        if (blockedTimeMs > HIGH_BLOCKED_THRESHOLD_MS) {
            return 80;  // Serious contention
        }
        return 60;  // Some contention
    }

    /**
     * Scores based on thread characteristics (daemon status, priority).
     */
    private int scoreThreadCharacteristics(ThreadInfo threadInfo) {
        int score = 0;

        // Non-daemon threads are more interesting (usually application threads)
        if (!threadInfo.isDaemon()) {
            score += 30;
        }

        // Higher priority threads might be more important
        if (threadInfo.getPriority() > Thread.NORM_PRIORITY) {
            score += 10;
        }

        return score;
    }

    /**
     * Scores based on thread name patterns.
     */
    private int scoreThreadName(String threadName, long threadId) {
        int score = 0;

        // Heavy penalty for well-known JVM system threads
        if (JVM_SYSTEM_THREADS.contains(threadName)) {
            return -100;
        }

        // Penalty for GC and compiler threads
        if (GC_THREAD_PATTERN.matcher(threadName).matches()) {
            return -100;
        }

        // Bonus for thread pool threads (managed concurrency)
        if (THREAD_POOL_PATTERN.matcher(threadName).matches()) {
            score += 20;
        }

        // Bonus for early thread IDs (core application threads)
        if (threadId < EARLY_THREAD_ID_THRESHOLD) {
            score += 10;
        }

        return score;
    }

    /**
     * Scores based on stack trace content (application vs library code).
     */
    private int scoreStackTrace(ThreadInfo threadInfo) {
        if (appPackagePatterns.isEmpty()) {
            return 0;  // No app-specific configuration
        }

        StackTraceElement[] stackTrace = threadInfo.getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return 0;
        }

        // Check top frames for application code
        int framesToCheck = Math.min(5, stackTrace.length);
        for (int i = 0; i < framesToCheck; i++) {
            String className = stackTrace[i].getClassName();
            for (String appPackage : appPackagePatterns) {
                if (className.startsWith(appPackage)) {
                    return 40;  // Bonus for application code
                }
            }
        }

        return 0;
    }

    /**
     * Checks if a thread is a well-known JVM system thread.
     *
     * @param threadName Thread name to check
     * @return true if this is a JVM system thread
     */
    public static boolean isJvmSystemThread(String threadName) {
        return JVM_SYSTEM_THREADS.contains(threadName) ||
               GC_THREAD_PATTERN.matcher(threadName).matches();
    }

    /**
     * Checks if a thread is a thread pool thread.
     *
     * @param threadName Thread name to check
     * @return true if this is a thread pool thread
     */
    public static boolean isThreadPoolThread(String threadName) {
        return THREAD_POOL_PATTERN.matcher(threadName).matches();
    }
}
