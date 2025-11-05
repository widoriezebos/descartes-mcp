package com.bitsapplied.descartes.debugger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple test application for debugger testing.
 *
 * <p>
 * This application provides various methods with different execution patterns
 * that can be used to test debugger features like:
 * <ul>
 * <li>Breakpoints (line breakpoints, method breakpoints)</li>
 * <li>Stepping (step over, step into, step out)</li>
 * <li>Variable inspection (primitives, objects, arrays)</li>
 * <li>Expression evaluation (simple and complex expressions)</li>
 * <li>Watch expressions</li>
 * </ul>
 */
public class SimpleTestApplication {

  private static final AtomicBoolean running = new AtomicBoolean(true);
  private int instanceCounter = 0;
  private String status = "INITIAL";

  /**
   * Main entry point for standalone execution.
   *
   * @param args command line arguments - pass "--continuous" to run indefinitely
   */
  public static void main(String[] args) {
    SimpleTestApplication app = new SimpleTestApplication();

    // Check for continuous mode flag
    boolean continuous = args.length > 0 && "--continuous".equals(args[0]);

    if (continuous) {
      System.out.println("Starting test application in CONTINUOUS mode...");
      try {
        app.runContinuously();
      } catch (InterruptedException e) {
        System.err.println("Test application interrupted, shutting down");
        Thread.currentThread().interrupt();
      }
    } else {
      app.runTestScenarios();
    }
  }

  /**
   * Runs various test scenarios for debugger testing.
   */
  public void runTestScenarios() {
    System.out.println("Starting test application...");
    status = "RUNNING";

    // Scenario 1: Simple arithmetic
    int result1 = calculateSum(5, 10);
    System.out.println("Sum result: " + result1);

    // Scenario 2: Loop with variables
    int result2 = calculateFactorial(5);
    System.out.println("Factorial result: " + result2);

    // Scenario 3: Object manipulation
    List<String> items = createList();
    processItems(items);

    // Scenario 4: Conditional logic
    String grade = determineGrade(85);
    System.out.println("Grade: " + grade);

    // Scenario 5: Method calls chain
    int chainResult = methodA(10);
    System.out.println("Chain result: " + chainResult);

    status = "COMPLETED";
    System.out.println("Test application completed.");
  }

  /**
   * Calculates the sum of two numbers.
   * <p>
   * Good for testing: basic breakpoints, simple variable inspection.
   *
   * @param a first number
   * @param b second number
   * @return sum of a and b
   */
  public int calculateSum(int a, int b) {
    int sum = a + b; // Breakpoint here to inspect a, b, sum
    return sum;
  }

  /**
   * Calculates factorial using a loop.
   * <p>
   * Good for testing: stepping through loops, watch expressions.
   *
   * @param n the number
   * @return factorial of n
   */
  public int calculateFactorial(int n) {
    int result = 1;

    for (int i = 1; i <= n; i++) { // Step through this loop
      result *= i; // Watch expression for result
    }

    return result;
  }

  /**
   * Creates a list of items.
   * <p>
   * Good for testing: object inspection, collection inspection.
   *
   * @return list of items
   */
  public List<String> createList() {
    List<String> items = new ArrayList<>();
    items.add("item1");
    items.add("item2");
    items.add("item3");
    return items; // Inspect items list
  }

  /**
   * Processes items from a list.
   * <p>
   * Good for testing: stepping through enhanced for loop.
   *
   * @param items list of items to process
   */
  public void processItems(List<String> items) {
    for (String item : items) { // Step into this loop
      String processed = item.toUpperCase();
      System.out.println("Processed: " + processed);
    }
  }

  /**
   * Determines grade based on score.
   * <p>
   * Good for testing: conditional breakpoints, expression evaluation.
   *
   * @param score the score
   * @return grade letter
   */
  public String determineGrade(int score) {
    String grade;

    if (score >= 90) { // Conditional breakpoint: score >= 90
      grade = "A";
    } else if (score >= 80) { // Breakpoint with condition: score < 90 && score >= 80
      grade = "B";
    } else if (score >= 70) {
      grade = "C";
    } else if (score >= 60) {
      grade = "D";
    } else {
      grade = "F";
    }

    return grade; // Evaluate expression: grade.equals("A") || grade.equals("B")
  }

  /**
   * Method A - calls method B.
   * <p>
   * Good for testing: step into, step out, stack trace inspection.
   *
   * @param value input value
   * @return processed value
   */
  public int methodA(int value) {
    int doubled = value * 2;
    int result = methodB(doubled); // Step into methodB
    return result;
  }

  /**
   * Method B - calls method C.
   *
   * @param value input value
   * @return processed value
   */
  private int methodB(int value) {
    int incremented = value + 1;
    int result = methodC(incremented); // Step into methodC
    return result;
  }

  /**
   * Method C - final method in the chain.
   *
   * @param value input value
   * @return processed value
   */
  private int methodC(int value) {
    int squared = value * value; // Step out from here back to methodB
    return squared;
  }

  /**
   * Method with multiple variable types for inspection.
   * <p>
   * Good for testing: variable inspection of different types.
   *
   * @return true always
   */
  public boolean testVariableTypes() {
    // Primitive types
    byte byteVar = 127;
    short shortVar = 32767;
    int intVar = 42;
    long longVar = 123456789L;
    float floatVar = 3.14f;
    double doubleVar = 2.718281828;
    char charVar = 'A';
    boolean boolVar = true;

    // String
    String stringVar = "Hello, Debugger!";

    // Array
    int[] arrayVar = { 1, 2, 3, 4, 5 };

    // Object
    TestObject objectVar = new TestObject("test", 100);

    // All variables should be inspectable here
    // Prevent dead code elimination by using all variables
    return boolVar && byteVar > 0 && shortVar > 0 && intVar > 0 && longVar > 0 && floatVar > 0 && doubleVar > 0
        && charVar > 0 && stringVar.length() > 0 && arrayVar.length > 0 && objectVar.getValue() > 0; // Breakpoint
                                                                                                     // here to
                                                                                                     // inspect all
                                                                                                     // variables
  }

  /**
   * Method that throws an exception (for exception handling testing).
   *
   * @param shouldThrow whether to throw exception
   * @return success message
   */
  public String testExceptionHandling(boolean shouldThrow) {
    try {
      if (shouldThrow) {
        throw new IllegalArgumentException("Test exception");
      }
      return "Success";
    } catch (IllegalArgumentException e) {
      System.err.println("Caught exception: " + e.getMessage());
      return "Exception caught"; // Breakpoint here to inspect exception
    }
  }

  /**
   * Continuous running method for attach/detach testing.
   *
   * @throws InterruptedException if interrupted
   */
  public void runContinuously() throws InterruptedException {
    while (running.get()) {
      instanceCounter++;
      Thread.sleep(1000);

      if (instanceCounter % 5 == 0) {
        System.out.println("Counter: " + instanceCounter);
      }
    }
  }

  /**
   * Stops the continuous running method.
   */
  public void stop() {
    running.set(false);
  }

  /**
   * Gets the current status.
   *
   * @return status string
   */
  public String getStatus() {
    return status;
  }

  /**
   * Gets the instance counter.
   *
   * @return counter value
   */
  public int getInstanceCounter() {
    return instanceCounter;
  }

  /**
   * Simple test object for object inspection.
   */
  public static class TestObject {
    private final String name;
    private int value;

    public TestObject(String name, int value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public int getValue() {
      return value;
    }

    public void setValue(int value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return "TestObject{name='" + name + "', value=" + value + "}";
    }
  }
}
