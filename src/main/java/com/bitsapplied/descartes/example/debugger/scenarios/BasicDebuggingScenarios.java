package com.bitsapplied.descartes.example.debugger.scenarios;

import java.util.List;

/**
 * Basic debugging scenarios demonstrating fundamental debugger operations.
 *
 * <p>
 * This class provides simple, clear examples for:
 * <ul>
 * <li>Setting breakpoints and stepping through code</li>
 * <li>Inspecting variables (primitives, objects, strings)</li>
 * <li>Evaluating expressions in context</li>
 * <li>Using watch expressions</li>
 * <li>Step over, step into, step out operations</li>
 * </ul>
 *
 * <h3>Suggested Debugging Workflow:</h3>
 * <ol>
 * <li>Set breakpoint at line in {@link #simpleCalculation()}</li>
 * <li>Step through with stepOver to see variable changes</li>
 * <li>Use variable inspector to examine values</li>
 * <li>Try expression evaluation to test hypotheses</li>
 * <li>Set watch on {@code result} variable</li>
 * </ol>
 */
public class BasicDebuggingScenarios {

  /**
   * Simple arithmetic calculation with multiple steps. Good for practicing
   * breakpoints and stepping.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint on line with {@code a = 10}</li>
   * <li>Step over each line and watch variables change</li>
   * <li>Evaluate expression: {@code a + b} before calculation</li>
   * </ul>
   *
   * @return The final result
   */
  public int simpleCalculation() {
    int a = 10; // Breakpoint here
    int b = 20; // Step over to here
    int sum = a + b; // Inspect variables: a, b, sum
    int product = a * b; // Evaluate: sum + product
    int result = sum + product;
    return result; // Result should be 230
  }

  /**
   * Demonstrates variable inspection with different types. Shows primitives,
   * objects, and strings.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after all variables are initialized</li>
   * <li>Inspect each variable type</li>
   * <li>Expand the {@code person} object to see fields</li>
   * <li>Watch expression: {@code person.name.length()}</li>
   * </ul>
   */
  public void variableInspection() {
    // Primitives
    int count = 42;
    double price = 19.99;
    boolean isActive = true;
    char grade = 'A';

    // Objects
    String message = "Hello, Debugger!";
    Person person = new Person("Alice", 30);

    // Breakpoint here - inspect all variables
    System.out.println("Count: " + count);
    System.out.println("Price: " + price);
    System.out.println("Active: " + isActive);
    System.out.println("Grade: " + grade);
    System.out.println("Message: " + message);
    System.out.println("Person: " + person);
  }

  /**
   * Method calling chain to demonstrate step into/out.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint on {@code int x = getValue()}</li>
   * <li>Use stepInto to enter {@link #getValue()}</li>
   * <li>Use stepOut to return to caller</li>
   * <li>Use stepInto on {@link #processValue(int)}</li>
   * </ul>
   *
   * @return Processed result
   */
  public int methodCallChain() {
    System.out.println("Starting method call chain");

    int x = getValue(); // Breakpoint here, stepInto
    int y = processValue(x); // stepInto this too
    int z = finalizeValue(y); // and this

    return z;
  }

  /**
   * Helper method for step into demonstration.
   * 
   * @return A computed value
   */
  private int getValue() {
    int base = 10;
    int multiplier = 5;
    return base * multiplier; // stepOut from here
  }

  /**
   * Helper method for step into demonstration.
   * 
   * @param input Input value to process
   * @return Processed value
   */
  private int processValue(int input) {
    int adjustment = 7;
    return input + adjustment;
  }

  /**
   * Helper method for step into demonstration.
   * 
   * @param input Input value to finalize
   * @return Final value
   */
  private int finalizeValue(int input) {
    return input * 2;
  }

  /**
   * Conditional logic good for expression evaluation.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint before the if statement</li>
   * <li>Evaluate: {@code score >= 90}</li>
   * <li>Evaluate: {@code score >= 80 && score < 90}</li>
   * <li>Set conditional breakpoint: {@code score >= 90}</li>
   * </ul>
   *
   * @param score Student score
   * @return Letter grade
   */
  public String calculateGrade(int score) {
    String grade;

    // Breakpoint here - try evaluating conditions
    if (score >= 90) {
      grade = "A";
    } else if (score >= 80) {
      grade = "B";
    } else if (score >= 70) {
      grade = "C";
    } else if (score >= 60) {
      grade = "D";
    } else {
      grade = "F";
    }

    return grade;
  }

  /**
   * Loop iteration good for watch expressions.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint inside loop body</li>
   * <li>Add watch: {@code i}</li>
   * <li>Add watch: {@code sum}</li>
   * <li>Add watch: {@code numbers.get(i)}</li>
   * <li>Resume to see watch values update</li>
   * </ul>
   *
   * @return Sum of numbers
   */
  public int loopWithWatch() {
    List<Integer> numbers = List.of(10, 20, 30, 40, 50);
    int sum = 0;

    for (int i = 0; i < numbers.size(); i++) {
      int current = numbers.get(i); // Breakpoint here
      sum += current; // Watch 'sum' change
    }

    return sum; // Should be 150
  }

  /**
   * String manipulation for object inspection.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint after string operations</li>
   * <li>Inspect {@code parts} array</li>
   * <li>Expand to see individual elements</li>
   * <li>Evaluate: {@code parts[0].toUpperCase()}</li>
   * </ul>
   */
  public void stringManipulation() {
    String input = "hello,world,debugger";
    String[] parts = input.split(",");

    // Breakpoint here
    for (String part : parts) {
      String capitalized = part.toUpperCase();
      System.out.println(capitalized);
    }
  }

  /**
   * Run all basic scenarios in sequence. Useful for automated demonstration.
   */
  public void runAllScenarios() {
    System.out.println("\n=== Basic Debugging Scenarios ===\n");

    System.out.println("1. Simple Calculation:");
    int result1 = simpleCalculation();
    System.out.println("   Result: " + result1);

    System.out.println("\n2. Variable Inspection:");
    variableInspection();

    System.out.println("\n3. Method Call Chain:");
    int result3 = methodCallChain();
    System.out.println("   Final result: " + result3);

    System.out.println("\n4. Calculate Grade:");
    String gradeA = calculateGrade(95);
    String gradeB = calculateGrade(85);
    String gradeF = calculateGrade(45);
    System.out.println("   95 -> " + gradeA);
    System.out.println("   85 -> " + gradeB);
    System.out.println("   45 -> " + gradeF);

    System.out.println("\n5. Loop with Watch:");
    int sum = loopWithWatch();
    System.out.println("   Sum: " + sum);

    System.out.println("\n6. String Manipulation:");
    stringManipulation();

    System.out.println("\n=== Basic Scenarios Complete ===\n");
  }

  /**
   * Simple Person class for object inspection demonstration.
   */
  public static class Person {
    private final String name;
    private final int age;
    private final String email;

    public Person(String name, int age) {
      this.name = name;
      this.age = age;
      this.email = name.toLowerCase().replace(" ", ".") + "@example.com";
    }

    public String getName() {
      return name;
    }

    public int getAge() {
      return age;
    }

    public String getEmail() {
      return email;
    }

    @Override
    public String toString() {
      return "Person{name='" + name + "', age=" + age + ", email='" + email + "'}";
    }
  }
}
