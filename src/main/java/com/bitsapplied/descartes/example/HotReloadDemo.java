package com.bitsapplied.descartes.example;

/**
 * Demo class for testing hot reload functionality. Modify the getMessage()
 * method and reload to see changes without restart.
 */
public class HotReloadDemo {

  private static int counter = 0;

  /**
   * Get a message. Modify this method and hot reload to see changes.
   * 
   * @return A message string
   */
  public static String getMessage() {
    counter++;
    // MODIFY THIS LINE AND HOT RELOAD TO SEE CHANGES
    return "Hello from HotReloadDemo! Counter: " + counter + " (Version 1)";
  }

  /**
   * Test method that can be called from JShell.
   */
  public static void test() {
    System.out.println("Testing hot reload:");
    System.out.println(getMessage());
    System.out.println("Class loader: " + HotReloadDemo.class.getClassLoader());
    System.out.println("Code source: " + HotReloadDemo.class.getProtectionDomain().getCodeSource().getLocation());
  }
}