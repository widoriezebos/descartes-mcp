package com.bitsapplied.descartes.example.debugger.scenarios;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Exception handling scenarios for debugging exception flows.
 *
 * <p>
 * This class demonstrates debugger capabilities for:
 * <ul>
 * <li>Catching exceptions at throw point</li>
 * <li>Inspecting exception objects and stack traces</li>
 * <li>Debugging exception handlers</li>
 * <li>Uncaught exception debugging</li>
 * <li>Exception chaining and cause analysis</li>
 * </ul>
 *
 * <h3>Debugging Focus:</h3>
 * <ul>
 * <li>Breakpoints on exception throw/catch</li>
 * <li>Stack trace inspection during exception</li>
 * <li>Variable state at exception point</li>
 * <li>Exception object inspection</li>
 * </ul>
 */
public class ExceptionScenarios {

  /**
   * Simple checked exception handling.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint on throw statement</li>
   * <li>Inspect variables when exception is thrown</li>
   * <li>Step into catch block</li>
   * <li>Inspect exception object (message, cause, stack trace)</li>
   * </ul>
   */
  public void checkedExceptionHandling() {
    System.out.println("Testing checked exception...");

    try {
      readFile("nonexistent.txt"); // Will throw IOException
    } catch (IOException e) {
      // Breakpoint here - inspect exception
      System.out.println("Caught IOException: " + e.getMessage());
    }
  }

  private void readFile(String filename) throws IOException {
    if (!filename.equals("valid.txt")) {
      throw new FileNotFoundException("File not found: " + filename); // Breakpoint here
    }
    System.out.println("File read successfully");
  }

  /**
   * Unchecked exception (NullPointerException).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint before NPE occurs</li>
   * <li>Inspect {@code person} variable (it's null!)</li>
   * <li>Use conditional breakpoint: {@code person == null}</li>
   * <li>Step to see exactly where NPE is thrown</li>
   * </ul>
   */
  @SuppressWarnings("null") // Intentional NPE for debugging demonstration
  public void nullPointerException() {
    System.out.println("Testing null pointer exception...");

    Person person = null; // Intentionally null - DO NOT FIX, this is for demo purposes

    try {
      // Breakpoint here - person is null
      String name = person.getName(); // Will throw NPE - INTENTIONAL for demo
      System.out.println("Name: " + name);
    } catch (NullPointerException e) {
      System.out.println("Caught NPE: " + e.getMessage());
    }
  }

  /**
   * IllegalArgumentException with validation.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in validation logic</li>
   * <li>Inspect {@code age} value</li>
   * <li>Watch conditional: {@code age < 0 || age > 150}</li>
   * <li>Step into exception throw</li>
   * </ul>
   */
  public void illegalArgumentException() {
    System.out.println("Testing illegal argument exception...");

    try {
      validateAge(-5); // Invalid age
    } catch (IllegalArgumentException e) {
      System.out.println("Caught IllegalArgumentException: " + e.getMessage());
    }

    try {
      validateAge(200); // Invalid age
    } catch (IllegalArgumentException e) {
      System.out.println("Caught IllegalArgumentException: " + e.getMessage());
    }

    validateAge(30); // Valid age
  }

  private void validateAge(int age) {
    if (age < 0 || age > 150) {
      // Breakpoint here - inspect age
      throw new IllegalArgumentException("Invalid age: " + age + " (must be 0-150)");
    }
    System.out.println("Age " + age + " is valid");
  }

  /**
   * Deep call stack exception.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint where exception is thrown</li>
   * <li>Use debugger_stacktrace to see full call chain</li>
   * <li>Inspect variables at each stack frame</li>
   * <li>Notice how exception propagates up the stack</li>
   * </ul>
   */
  public void deepCallStackException() {
    System.out.println("Testing deep call stack exception...");

    try {
      methodA();
    } catch (RuntimeException e) {
      System.out.println("Caught exception from deep call: " + e.getMessage());
      System.out.println("Stack depth: " + e.getStackTrace().length);
    }
  }

  private void methodA() {
    methodB(10);
  }

  private void methodB(int value) {
    methodC(value * 2);
  }

  private void methodC(int value) {
    methodD(value + 5);
  }

  private void methodD(int value) {
    // Breakpoint here - deep in the call stack
    if (value > 20) {
      throw new RuntimeException("Value too large: " + value);
    }
  }

  /**
   * Exception chaining (cause analysis).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint on outer catch</li>
   * <li>Inspect exception object</li>
   * <li>Expand to see {@code cause} field</li>
   * <li>Inspect original exception details</li>
   * <li>Compare stack traces of both exceptions</li>
   * </ul>
   */
  public void exceptionChaining() {
    System.out.println("Testing exception chaining...");

    try {
      performOperation();
    } catch (OperationException e) {
      // Breakpoint here - inspect exception and its cause
      System.out.println("Caught OperationException: " + e.getMessage());
      System.out.println("Caused by: " + e.getCause().getMessage());
    }
  }

  private void performOperation() throws OperationException {
    try {
      riskyOperation();
    } catch (IOException e) {
      // Wrap original exception
      throw new OperationException("Operation failed", e);
    }
  }

  private void riskyOperation() throws IOException {
    // Simulate IO error
    throw new IOException("Network timeout"); // Original exception
  }

  /**
   * Finally block debugging.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoints in try, catch, and finally blocks</li>
   * <li>Step through to see execution order</li>
   * <li>Notice finally always executes</li>
   * <li>Inspect {@code resource} state in each block</li>
   * </ul>
   */
  public void finallyBlockDebugging() {
    System.out.println("Testing finally block...");

    Resource resource = new Resource("TestResource");

    try {
      System.out.println("Try block: using resource");
      resource.use();

      if (Math.random() > 0.5) {
        throw new RuntimeException("Random failure");
      }

      System.out.println("Try block: success");
    } catch (RuntimeException e) {
      // Breakpoint here
      System.out.println("Catch block: " + e.getMessage());
    } finally {
      // Breakpoint here - always executes
      System.out.println("Finally block: cleaning up");
      resource.close();
    }
  }

  /**
   * Custom exception with additional fields.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint on catch</li>
   * <li>Inspect custom exception object</li>
   * <li>Expand to see custom fields (errorCode, details)</li>
   * <li>Notice additional context beyond standard exception</li>
   * </ul>
   */
  public void customExceptionInspection() {
    System.out.println("Testing custom exception...");

    try {
      processTransaction(1000);
    } catch (InsufficientFundsException e) {
      // Breakpoint here - inspect custom exception
      System.out.println("Transaction failed!");
      System.out.println("Error code: " + e.getErrorCode());
      System.out.println("Requested: " + e.getRequestedAmount());
      System.out.println("Available: " + e.getAvailableAmount());
    }
  }

  private void processTransaction(int amount) throws InsufficientFundsException {
    int balance = 500;

    if (amount > balance) {
      // Breakpoint here
      throw new InsufficientFundsException("Insufficient funds for transaction", "ERR_001", amount, balance);
    }

    System.out.println("Transaction successful");
  }

  /**
   * Run all exception scenarios.
   */
  public void runAllScenarios() {
    System.out.println("\n=== Exception Scenarios ===\n");

    System.out.println("1. Checked Exception:");
    checkedExceptionHandling();

    System.out.println("\n2. Null Pointer Exception:");
    nullPointerException();

    System.out.println("\n3. Illegal Argument Exception:");
    illegalArgumentException();

    System.out.println("\n4. Deep Call Stack Exception:");
    deepCallStackException();

    System.out.println("\n5. Exception Chaining:");
    exceptionChaining();

    System.out.println("\n6. Finally Block:");
    finallyBlockDebugging();

    System.out.println("\n7. Custom Exception:");
    customExceptionInspection();

    System.out.println("\n=== Exception Scenarios Complete ===\n");
  }

  // ============================================================================
  // Supporting classes
  // ============================================================================

  /**
   * Simple Person class for NPE demonstration. Constructor intentionally unused -
   * we use null reference for NPE demo.
   */
  @SuppressWarnings("unused") // Constructor intentionally unused - null reference used for NPE demo
  private static class Person {
    private final String name;

    public Person(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

  /**
   * Resource class for finally block demonstration.
   */
  private static class Resource {
    private final String name;
    private boolean open = true;

    public Resource(String name) {
      this.name = name;
      System.out.println("Resource '" + name + "' opened");
    }

    public void use() {
      if (!open) {
        throw new IllegalStateException("Resource is closed");
      }
      System.out.println("Using resource '" + name + "'");
    }

    public void close() {
      if (open) {
        open = false;
        System.out.println("Resource '" + name + "' closed");
      }
    }
  }

  /**
   * Custom exception for operation failures. Demo class - serialization not used.
   */
  @SuppressWarnings("serial") // Demo exception class - serialization not used
  private static class OperationException extends Exception {
    public OperationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Custom exception with additional context fields. Demo class - serialization
   * not used.
   */
  @SuppressWarnings("serial") // Demo exception class - serialization not used
  private static class InsufficientFundsException extends Exception {
    private final String errorCode;
    private final int requestedAmount;
    private final int availableAmount;

    public InsufficientFundsException(String message, String errorCode, int requestedAmount, int availableAmount) {
      super(message);
      this.errorCode = errorCode;
      this.requestedAmount = requestedAmount;
      this.availableAmount = availableAmount;
    }

    public String getErrorCode() {
      return errorCode;
    }

    public int getRequestedAmount() {
      return requestedAmount;
    }

    public int getAvailableAmount() {
      return availableAmount;
    }
  }
}
