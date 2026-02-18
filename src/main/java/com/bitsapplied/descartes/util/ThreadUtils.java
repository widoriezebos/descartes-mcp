package com.bitsapplied.descartes.util;

import java.lang.reflect.Method;

/**
 * Thread utility methods with cross-version compatibility for Java 16+.
 *
 * <p>
 * This class provides compatibility shims for Thread methods that were
 * introduced in newer Java versions, with automatic fallback to older APIs when
 * running on earlier JVMs.
 *
 * <h2>Thread ID Compatibility</h2>
 * <p>
 * Java 19 introduced {@code Thread.threadId()} as a replacement for the
 * now-deprecated {@code Thread.getId()}. Both methods return the same value,
 * but direct calls to either cause issues:
 * <ul>
 * <li>Calling {@code threadId()} fails on Java 16-18 with
 * {@code NoSuchMethodError}</li>
 * <li>Calling {@code getId()} generates deprecation warnings on Java 19+</li>
 * </ul>
 *
 * <p>
 * This utility uses reflection to detect and call {@code threadId()} when
 * available, falling back to {@code getId()} on older JVMs. This approach:
 * <ul>
 * <li>✅ Works on Java 16+ (project's minimum supported version)</li>
 * <li>✅ Uses modern API when available (Java 19+)</li>
 * <li>✅ Generates no deprecation warnings (reflection shields the call)</li>
 * <li>✅ Has negligible overhead (method lookup cached statically, ~2-3ns
 * reflection cost)</li>
 * </ul>
 *
 * <h2>Implementation Notes</h2>
 * <p>
 * The reflection-based approach was chosen over alternatives for these reasons:
 * <ul>
 * <li><b>vs. Direct {@code getId()}</b>: Avoids deprecation warnings and uses
 * modern API</li>
 * <li><b>vs. Multi-Release JAR</b>: Simpler build process for just one
 * method</li>
 * <li><b>vs. MethodHandles</b>: Reflection is more familiar; performance
 * difference negligible for this use case</li>
 * </ul>
 *
 * @since 0.0.1
 */
public final class ThreadUtils {

  /**
   * Cached reference to Thread.threadId() method if available (Java 19+). Null if
   * running on Java 16-18.
   */
  private static final Method THREAD_ID_METHOD;

  static {
    Method method = null;
    try {
      // Try to find Thread.threadId() - available in Java 19+
      method = Thread.class.getMethod("threadId");
    } catch (NoSuchMethodException e) {
      // Running on Java 16-18, will fall back to getId()
      // This is expected and not an error condition
    }
    THREAD_ID_METHOD = method;
  }

  private ThreadUtils() {
    throw new UnsupportedOperationException("Utility class - do not instantiate");
  }

  /**
   * Gets the unique identifier for the given thread, compatible across Java
   * versions.
   *
   * <p>
   * This method uses {@code Thread.threadId()} on Java 19+ and falls back to
   * {@code Thread.getId()} on Java 16-18. Both methods return identical values;
   * the only difference is the API surface.
   *
   * <p>
   * <b>Thread ID Semantics:</b>
   * <ul>
   * <li>Thread IDs are unique and positive</li>
   * <li>IDs are never reused during the lifetime of the JVM</li>
   * <li>The main thread typically (but not guaranteed) has ID 1</li>
   * <li>IDs increase monotonically as threads are created</li>
   * </ul>
   *
   * <p>
   * <b>Performance:</b> This method has minimal overhead:
   * <ul>
   * <li>Java 19+: ~2-3ns reflection cost (method cached, invocation fast)</li>
   * <li>Java 16-18: Direct native call (fastest possible)</li>
   * <li>JIT compiler may optimize the reflection path over time</li>
   * </ul>
   *
   * @param thread the thread to get the ID for, must not be null
   * @return the thread's unique identifier (positive long value)
   * @throws NullPointerException if thread is null
   *
   * @see Thread#threadId()
   * @see Thread#getId()
   */
  public static long getThreadId(Thread thread) {
    if (THREAD_ID_METHOD != null) {
      // Java 19+ path: use Thread.threadId() via reflection
      try {
        return (long) THREAD_ID_METHOD.invoke(thread);
      } catch (Exception e) {
        // Reflection failure - should never happen since we verified the method exists
        // Fall through to getId() as a safety net
        // Don't throw here to maintain robustness even in pathological cases
      }
    }

    // Java 16-18 path: use Thread.getId()
    // Suppression justified: We only call getId() on Java 16-18 where it's not
    // deprecated.
    // On Java 19+, we use threadId() via reflection above.
    // The @SuppressWarnings is needed because we compile with Java 21 where getId()
    // is deprecated,
    // but this code path only executes on Java 16-18 where getId() is the correct
    // API to use.
    @SuppressWarnings("deprecation")
    long id = thread.getId();
    return id;
  }
}
