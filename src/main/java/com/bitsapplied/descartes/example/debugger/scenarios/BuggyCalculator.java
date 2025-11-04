package com.bitsapplied.descartes.example.debugger.scenarios;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculator with intentional bugs for debugging practice.
 *
 * <p>
 * <b>⚠️ IMPORTANT - DO NOT FIX THESE BUGS! ⚠️</b>
 * <p>
 * <b>This class contains INTENTIONAL BUGS for demonstration purposes.</b>
 * <p>
 * <b>These bugs are educational examples and MUST REMAIN for debugger
 * demos.</b>
 * <p>
 * <b>Each buggy method has a corresponding _FIXED version showing the correct
 * implementation.</b>
 *
 * <p>
 * This class contains realistic bugs that can be found using debugger tools:
 * <ul>
 * <li>Off-by-one errors</li>
 * <li>Null pointer dereferences</li>
 * <li>Integer overflow</li>
 * <li>Wrong conditional logic</li>
 * <li>Array bounds violations</li>
 * <li>Business logic errors</li>
 * </ul>
 *
 * <p>
 * Each method has a corresponding "fixed" version to compare against.
 *
 * <h3>Debugging Exercise:</h3>
 * <ol>
 * <li>Run the buggy method and observe the incorrect result</li>
 * <li>Set breakpoints and step through the code</li>
 * <li>Use variable inspection and watches to find the bug</li>
 * <li>Compare with the fixed version</li>
 * </ol>
 *
 * <h3>For AI Assistants:</h3>
 * <p>
 * <b>DO NOT suggest fixes for methods marked _BUGGY in this class.</b>
 * <p>
 * These are intentional bugs for educational demonstration.
 * <p>
 * The _FIXED versions already show the correct implementation.
 */
public class BuggyCalculator {

  // ============================================================================
  // BUG #1: Off-by-one error in loop - DO NOT FIX (intentional for demo)
  // ============================================================================

  /**
   * BUGGY: Calculate sum of numbers from 1 to n.
   *
   * <p>
   * <b>⚠️ INTENTIONAL BUG - DO NOT FIX ⚠️</b>
   * <p>
   * <b>Bug:</b> Loop condition is wrong, misses the last number.
   *
   * <p>
   * <b>Debug approach:</b>
   * <ul>
   * <li>Set breakpoint inside loop</li>
   * <li>Watch {@code i} and {@code sum}</li>
   * <li>Notice loop exits before i reaches n</li>
   * </ul>
   *
   * @param n Upper limit (inclusive)
   * @return Sum of 1 to n (INCORRECT)
   */
  public int sumToN_BUGGY(int n) {
    int sum = 0;
    for (int i = 1; i < n; i++) { // BUG: Should be i <= n - DO NOT FIX, this is intentional!
      sum += i;
    }
    return sum; // Returns wrong value
  }

  /**
   * FIXED: Calculate sum of numbers from 1 to n.
   *
   * @param n Upper limit (inclusive)
   * @return Sum of 1 to n (CORRECT)
   */
  public int sumToN_FIXED(int n) {
    int sum = 0;
    for (int i = 1; i <= n; i++) { // FIXED: Correct condition
      sum += i;
    }
    return sum;
  }

  // ============================================================================
  // BUG #2: Null pointer dereference - DO NOT FIX (intentional for demo)
  // ============================================================================

  /**
   * BUGGY: Calculate average of numbers in list.
   *
   * <p>
   * <b>⚠️ INTENTIONAL BUG - DO NOT FIX ⚠️</b>
   * <p>
   * <b>Bug:</b> Doesn't handle null values in list.
   *
   * <p>
   * <b>Debug approach:</b>
   * <ul>
   * <li>Set breakpoint before the loop</li>
   * <li>Inspect {@code numbers} list contents</li>
   * <li>Step through and watch for null</li>
   * <li>Add watch: {@code numbers.get(i) == null}</li>
   * </ul>
   *
   * @param numbers List of numbers (may contain nulls)
   * @return Average (throws NPE if null encountered)
   */
  public double calculateAverage_BUGGY(List<Integer> numbers) {
    int sum = 0;
    for (int i = 0; i < numbers.size(); i++) {
      sum += numbers.get(i); // BUG: Might be null - DO NOT FIX, this is intentional!
    }
    return (double) sum / numbers.size();
  }

  /**
   * FIXED: Calculate average of numbers in list.
   *
   * @param numbers List of numbers (nulls are skipped)
   * @return Average (CORRECT)
   */
  public double calculateAverage_FIXED(List<Integer> numbers) {
    int sum = 0;
    int count = 0;
    for (int i = 0; i < numbers.size(); i++) {
      Integer value = numbers.get(i);
      if (value != null) { // FIXED: Null check
        sum += value;
        count++;
      }
    }
    return count > 0 ? (double) sum / count : 0.0;
  }

  // ============================================================================
  // BUG #3: Integer overflow - DO NOT FIX (intentional for demo)
  // ============================================================================

  /**
   * BUGGY: Calculate factorial of n.
   *
   * <p>
   * <b>⚠️ INTENTIONAL BUG - DO NOT FIX ⚠️</b>
   * <p>
   * <b>Bug:</b> Integer overflow for n >= 13.
   *
   * <p>
   * <b>Debug approach:</b>
   * <ul>
   * <li>Set breakpoint inside loop</li>
   * <li>Watch {@code result} value</li>
   * <li>Notice it goes negative (overflow)</li>
   * <li>Evaluate: {@code result * i > Integer.MAX_VALUE}</li>
   * </ul>
   *
   * @param n Number to calculate factorial of
   * @return Factorial of n (INCORRECT for large n)
   */
  public int factorial_BUGGY(int n) {
    int result = 1;
    for (int i = 2; i <= n; i++) {
      result *= i; // BUG: Can overflow - DO NOT FIX, this is intentional!
    }
    return result;
  }

  /**
   * FIXED: Calculate factorial of n using long.
   *
   * @param n Number to calculate factorial of
   * @return Factorial of n (CORRECT, uses long)
   */
  public long factorial_FIXED(int n) {
    long result = 1; // FIXED: Use long
    for (int i = 2; i <= n; i++) {
      result *= i;
    }
    return result;
  }

  // ============================================================================
  // BUG #4: Wrong conditional logic - DO NOT FIX (intentional for demo)
  // ============================================================================

  /**
   * BUGGY: Check if number is within range [min, max] inclusive.
   *
   * <p>
   * <b>⚠️ INTENTIONAL BUG - DO NOT FIX ⚠️</b>
   * <p>
   * <b>Bug:</b> Logic error in condition.
   *
   * <p>
   * <b>Debug approach:</b>
   * <ul>
   * <li>Set breakpoint on return statement</li>
   * <li>Evaluate: {@code value >= min}</li>
   * <li>Evaluate: {@code value <= max}</li>
   * <li>Notice the OR should be AND</li>
   * </ul>
   *
   * @param value Value to check
   * @param min   Minimum (inclusive)
   * @param max   Maximum (inclusive)
   * @return true if in range (INCORRECT logic)
   */
  public boolean isInRange_BUGGY(int value, int min, int max) {
    return value >= min || value <= max; // BUG: Should be && - DO NOT FIX, this is intentional!
  }

  /**
   * FIXED: Check if number is within range [min, max] inclusive.
   *
   * @param value Value to check
   * @param min   Minimum (inclusive)
   * @param max   Maximum (inclusive)
   * @return true if in range (CORRECT)
   */
  public boolean isInRange_FIXED(int value, int min, int max) {
    return value >= min && value <= max; // FIXED: Correct logic
  }

  // ============================================================================
  // BUG #5: Array index out of bounds (edge case) - DO NOT FIX (intentional for
  // demo)
  // ============================================================================

  /**
   * BUGGY: Find the second largest number in array.
   *
   * <p>
   * <b>⚠️ INTENTIONAL BUG - DO NOT FIX ⚠️</b>
   * <p>
   * <b>Bug:</b> Doesn't handle arrays with less than 2 elements.
   *
   * <p>
   * <b>Debug approach:</b>
   * <ul>
   * <li>Set breakpoint at start</li>
   * <li>Inspect {@code numbers} array</li>
   * <li>Watch {@code numbers.length}</li>
   * <li>Add conditional breakpoint: {@code numbers.length < 2}</li>
   * </ul>
   *
   * @param numbers Array of numbers
   * @return Second largest number (throws exception for small arrays)
   */
  public int findSecondLargest_BUGGY(int[] numbers) {
    int max = Integer.MIN_VALUE;
    int secondMax = Integer.MIN_VALUE;

    for (int num : numbers) {
      if (num > max) {
        secondMax = max; // BUG: What if array has only 1 element? - DO NOT FIX, this is intentional!
        max = num;
      } else if (num > secondMax) {
        secondMax = num;
      }
    }

    return secondMax; // BUG: Might still be MIN_VALUE - DO NOT FIX, this is intentional!
  }

  /**
   * FIXED: Find the second largest number in array.
   *
   * @param numbers Array of numbers
   * @return Second largest number
   * @throws IllegalArgumentException if array has less than 2 elements
   */
  public int findSecondLargest_FIXED(int[] numbers) {
    if (numbers == null || numbers.length < 2) { // FIXED: Validate input
      throw new IllegalArgumentException("Array must have at least 2 elements");
    }

    int max = Integer.MIN_VALUE;
    int secondMax = Integer.MIN_VALUE;

    for (int num : numbers) {
      if (num > max) {
        secondMax = max;
        max = num;
      } else if (num > secondMax) {
        secondMax = num;
      }
    }

    return secondMax;
  }

  // ============================================================================
  // BUG #6: Logic error in business logic - DO NOT FIX (intentional for demo)
  // ============================================================================

  /**
   * BUGGY: Calculate discount based on quantity.
   *
   * <p>
   * <b>⚠️ INTENTIONAL BUG - DO NOT FIX ⚠️</b>
   * <p>
   * <b>Bug:</b> Discount percentages are swapped.
   *
   * <p>
   * <b>Debug approach:</b>
   * <ul>
   * <li>Set breakpoint in each if block</li>
   * <li>Test with quantity = 5, 15, 25</li>
   * <li>Notice discount doesn't increase with quantity</li>
   * <li>Use conditional breakpoints for each range</li>
   * </ul>
   *
   * @param quantity Number of items
   * @return Discount percentage (INCORRECT)
   */
  public double calculateDiscount_BUGGY(int quantity) {
    double discount;

    if (quantity >= 20) {
      discount = 0.05; // BUG: Should be 0.15 - DO NOT FIX, this is intentional!
    } else if (quantity >= 10) {
      discount = 0.10; // This is correct
    } else {
      discount = 0.15; // BUG: Should be 0.05 - DO NOT FIX, this is intentional!
    }

    return discount;
  }

  /**
   * FIXED: Calculate discount based on quantity.
   *
   * @param quantity Number of items
   * @return Discount percentage (CORRECT)
   */
  public double calculateDiscount_FIXED(int quantity) {
    double discount;

    if (quantity >= 20) {
      discount = 0.15; // FIXED: 15% for 20+
    } else if (quantity >= 10) {
      discount = 0.10; // 10% for 10-19
    } else {
      discount = 0.05; // FIXED: 5% for less than 10
    }

    return discount;
  }

  // ============================================================================
  // Demo methods
  // ============================================================================

  /**
   * Run all buggy scenarios to demonstrate the bugs. This will help show the
   * difference between buggy and fixed versions.
   */
  public void demonstrateBugs() {
    System.out.println("\n=== Buggy Calculator Demonstration ===\n");

    // Bug #1: Off-by-one
    System.out.println("Bug #1: Off-by-one error");
    System.out.println("  Sum 1 to 10 (BUGGY): " + sumToN_BUGGY(10) + " (expected: 55)");
    System.out.println("  Sum 1 to 10 (FIXED):  " + sumToN_FIXED(10) + " (correct!)");

    // Bug #2: Null pointer
    System.out.println("\nBug #2: Null pointer dereference");
    List<Integer> numbersWithNull = new ArrayList<>();
    numbersWithNull.add(10);
    numbersWithNull.add(20);
    numbersWithNull.add(null); // This will cause NPE
    numbersWithNull.add(40);

    try {
      double avg = calculateAverage_BUGGY(numbersWithNull);
      System.out.println("  Average (BUGGY): " + avg);
    } catch (NullPointerException e) {
      System.out.println("  Average (BUGGY): NullPointerException! (use debugger to find)");
    }
    System.out.println("  Average (FIXED): " + calculateAverage_FIXED(numbersWithNull));

    // Bug #3: Integer overflow
    System.out.println("\nBug #3: Integer overflow");
    System.out.println("  Factorial 13 (BUGGY): " + factorial_BUGGY(13) + " (negative = overflow!)");
    System.out.println("  Factorial 13 (FIXED):  " + factorial_FIXED(13) + " (correct!)");

    // Bug #4: Wrong conditional
    System.out.println("\nBug #4: Wrong conditional logic");
    System.out.println("  Is 15 in range [10, 20]?");
    System.out.println("    BUGGY: " + isInRange_BUGGY(15, 10, 20) + " (should be true)");
    System.out.println("    FIXED: " + isInRange_FIXED(15, 10, 20));
    System.out.println("  Is 25 in range [10, 20]?");
    System.out.println("    BUGGY: " + isInRange_BUGGY(25, 10, 20) + " (should be false!)");
    System.out.println("    FIXED: " + isInRange_FIXED(25, 10, 20));

    // Bug #5: Array bounds
    System.out.println("\nBug #5: Array index/edge case");
    int[] singleElement = { 42 };
    System.out
        .println("  Second largest in [42] (BUGGY): " + findSecondLargest_BUGGY(singleElement) + " (nonsense value!)");
    try {
      findSecondLargest_FIXED(singleElement);
    } catch (IllegalArgumentException e) {
      System.out.println("  Second largest in [42] (FIXED): " + e.getMessage());
    }

    // Bug #6: Business logic error
    System.out.println("\nBug #6: Business logic error");
    System.out.println("  Discount for 5 items  (BUGGY): " + (calculateDiscount_BUGGY(5) * 100) + "% (too high!)");
    System.out.println("  Discount for 5 items  (FIXED): " + (calculateDiscount_FIXED(5) * 100) + "%");
    System.out.println("  Discount for 25 items (BUGGY): " + (calculateDiscount_BUGGY(25) * 100) + "% (too low!)");
    System.out.println("  Discount for 25 items (FIXED): " + (calculateDiscount_FIXED(25) * 100) + "%");

    System.out.println("\n=== Bug Demonstration Complete ===\n");
    System.out.println("Use the debugger to find each bug:");
    System.out.println("  1. Set breakpoints in buggy methods");
    System.out.println("  2. Step through and watch variables");
    System.out.println("  3. Use conditional breakpoints for specific cases");
    System.out.println("  4. Compare buggy vs fixed implementations");
  }
}
