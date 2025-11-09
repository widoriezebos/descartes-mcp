package com.bitsapplied.descartes.debugger.models;

import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;

/**
 * Thread information record with virtual thread support.
 *
 * <p>
 * Captures the essential state of a thread in the debuggee, including:
 * <ul>
 * <li>Thread identity (ID and name)</li>
 * <li>Execution state (RUNNING, WAITING, MONITOR, etc.)</li>
 * <li>Suspension information (reason and location)</li>
 * <li>Virtual thread flag (JDK 21+)</li>
 * </ul>
 *
 * <p>
 * This record is immutable and thread-safe for sharing between components.
 */
public record ThreadInfo(long id, String name, String state, // Thread state as string (e.g., "RUNNING", "WAITING")
    boolean suspended, String suspendedReason, // e.g., "breakpoint", "step", null if not suspended
    Location suspendedLocation, // Can be null
    boolean isVirtual // IMPORTANT: Virtual thread flag for JDK 21+
) {

  /**
   * Create a builder for constructing ThreadInfo instances.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for ThreadInfo with fluent API.
   */
  public static class Builder {
    private long id;
    private String name;
    private String state;
    private boolean suspended;
    private String suspendedReason;
    private Location suspendedLocation;
    private boolean isVirtual;

    public Builder id(long id) {
      this.id = id;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder state(String state) {
      this.state = state;
      return this;
    }

    public Builder suspended(boolean suspended) {
      this.suspended = suspended;
      return this;
    }

    public Builder suspendedReason(String suspendedReason) {
      this.suspendedReason = suspendedReason;
      return this;
    }

    public Builder suspendedLocation(Location suspendedLocation) {
      this.suspendedLocation = suspendedLocation;
      return this;
    }

    public Builder isVirtual(boolean isVirtual) {
      this.isVirtual = isVirtual;
      return this;
    }

    public ThreadInfo build() {
      return new ThreadInfo(id, name, state, suspended, suspendedReason, suspendedLocation, isVirtual);
    }
  }

  /**
   * Creates a ThreadInfo from a JDWP ThreadReference.
   *
   * @param thread the thread reference from JDWP
   * @return a new ThreadInfo record
   * @throws com.sun.jdi.IncompatibleThreadStateException if thread state cannot
   *                                                      be accessed
   */
  public static ThreadInfo fromThread(ThreadReference thread) {
    // Get the current suspended location if suspended
    Location suspendedLoc = null;
    try {
      if (thread.isSuspended() && !thread.frames().isEmpty()) {
        suspendedLoc = thread.frame(0).location();
      }
    } catch (Exception e) {
      // Location may not be available; proceed without it
    }

    return new ThreadInfo(thread.uniqueID(), thread.name(), getThreadState(thread.status()), thread.isSuspended(), null, // Will
                                                                                                                         // be
                                                                                                                         // set
                                                                                                                         // by
                                                                                                                         // event
                                                                                                                         // handling
        suspendedLoc, thread.isVirtual() // JDK 21+ method - returns false on older JDKs if not virtual
    );
  }

  /**
   * Converts a JDWP thread status code to a human-readable state string.
   *
   * @param status the status code from ThreadReference.status()
   * @return the state as a string
   */
  private static String getThreadState(int status) {
    return switch (status) {
    case ThreadReference.THREAD_STATUS_RUNNING -> "RUNNING";
    case ThreadReference.THREAD_STATUS_SLEEPING -> "SLEEPING";
    case ThreadReference.THREAD_STATUS_MONITOR -> "MONITOR";
    case ThreadReference.THREAD_STATUS_WAIT -> "WAIT";
    case ThreadReference.THREAD_STATUS_NOT_STARTED -> "NOT_STARTED";
    case ThreadReference.THREAD_STATUS_ZOMBIE -> "ZOMBIE";
    default -> "UNKNOWN";
    };
  }

  /**
   * Checks if this thread is suspended at a specific location.
   *
   * @return true if both suspended and have location information
   */
  public boolean hasSuspendedLocation() {
    return suspended && suspendedLocation != null;
  }

  /**
   * Gets a short display string for this thread.
   *
   * @return formatted thread display
   */
  @Override
  public String toString() {
    String display = String.format("%s (id=%d, state=%s%s)", name, id, state, isVirtual ? ", virtual" : "");
    if (suspended && suspendedReason != null) {
      display += String.format(" [%s]", suspendedReason);
    }
    return display;
  }
}
