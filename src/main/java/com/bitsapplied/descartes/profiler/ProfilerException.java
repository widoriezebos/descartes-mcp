package com.bitsapplied.descartes.profiler;

/**
 * Exception thrown when profiling operations fail.
 */
public class ProfilerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ProfilerException(String message) {
    super(message);
  }

  public ProfilerException(String message, Throwable cause) {
    super(message, cause);
  }

  public ProfilerException(Throwable cause) {
    super(cause);
  }
}
