package com.bitsapplied.descartes.hotreload.test;

/**
 * Test class that will be modified and reloaded during hot reload testing. This
 * class is designed to be simple enough to modify its bytecode
 * programmatically.
 */
public class ReloadableTestClass {

  private static int version = 1;
  private String message = "Original";

  /**
   * Get the current version of this class. This method's implementation will be
   * changed during testing.
   */
  public static int getVersion() {
    // This will be modified to return different values
    return version;
  }

  /**
   * Get a test message. This method's implementation will be changed during
   * testing.
   */
  public String getMessage() {
    // This will be modified to return different messages
    return message;
  }

  /**
   * A method that performs a simple calculation. Used to test method body
   * modifications.
   */
  public int calculate(int a, int b) {
    // Original implementation: addition
    return a + b;
  }

  /**
   * Test method for verifying reload.
   */
  public String getIdentifier() {
    return "ReloadableTestClass-v" + version;
  }
}