package com.bitsapplied.descartes.example.debugger.scenarios;

/**
 * Call stack and stack trace scenarios for debugging.
 *
 * <p>
 * This class demonstrates debugger capabilities for:
 * <ul>
 * <li>Deep call chain inspection</li>
 * <li>Recursive method debugging</li>
 * <li>Stack frame navigation</li>
 * <li>Variable inspection at different stack depths</li>
 * <li>Filtered stack traces</li>
 * </ul>
 *
 * <h3>Debugging Focus:</h3>
 * <ul>
 * <li>Stack trace capture and inspection</li>
 * <li>Frame-by-frame variable examination</li>
 * <li>Recursion depth tracking</li>
 * <li>Stack filtering to hide framework code</li>
 * </ul>
 */
public class CallStackScenarios {

  /**
   * Deep call chain for stack inspection.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in {@link #level5(int)}</li>
   * <li>Use debugger_stacktrace to capture full stack</li>
   * <li>Navigate to each frame with getFrame(index)</li>
   * <li>Inspect variables at each level</li>
   * <li>Notice how {@code value} changes at each level</li>
   * </ul>
   */
  public void deepCallChain() {
    System.out.println("Starting deep call chain...");
    int result = level1(10);
    System.out.println("Final result: " + result);
  }

  private int level1(int value) {
    System.out.println("Level 1, value: " + value);
    return level2(value * 2);
  }

  private int level2(int value) {
    System.out.println("Level 2, value: " + value);
    return level3(value + 10);
  }

  private int level3(int value) {
    System.out.println("Level 3, value: " + value);
    return level4(value - 5);
  }

  private int level4(int value) {
    System.out.println("Level 4, value: " + value);
    return level5(value * 3);
  }

  private int level5(int value) {
    // Breakpoint here - inspect full call stack
    System.out.println("Level 5, value: " + value);
    return value / 2;
  }

  /**
   * Factorial calculation using recursion.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint at start of method</li>
   * <li>Set conditional breakpoint: {@code n == 3}</li>
   * <li>Examine stack trace showing recursive calls</li>
   * <li>Inspect {@code n} value at each recursion level</li>
   * <li>Watch stack depth grow and shrink</li>
   * </ul>
   *
   * @param n Number to calculate factorial of
   * @return Factorial of n
   */
  public long recursiveFactorial(int n) {
    // Breakpoint here - watch recursion
    if (n <= 1) {
      return 1; // Base case - stack will start unwinding
    }
    return n * recursiveFactorial(n - 1); // Recursive call
  }

  /**
   * Fibonacci using recursion (demonstrates multiple recursive calls).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in method body</li>
   * <li>Notice stack has duplicate frames (fib called twice per level)</li>
   * <li>Set conditional breakpoint: {@code n == 2}</li>
   * <li>Examine how many times fib(2) is called</li>
   * <li>See the branching nature of recursive calls</li>
   * </ul>
   *
   * @param n Fibonacci number to calculate
   * @return nth Fibonacci number
   */
  public long recursiveFibonacci(int n) {
    // Breakpoint here - see branching recursion
    if (n <= 1) {
      return n;
    }
    return recursiveFibonacci(n - 1) + recursiveFibonacci(n - 2);
  }

  /**
   * Tail-recursive factorial (optimizable).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in helper method</li>
   * <li>Notice accumulator pattern</li>
   * <li>Compare stack with regular factorial</li>
   * <li>Watch {@code accumulator} build up result</li>
   * </ul>
   *
   * @param n Number to calculate factorial of
   * @return Factorial of n
   */
  public long tailRecursiveFactorial(int n) {
    return tailFactorialHelper(n, 1);
  }

  private long tailFactorialHelper(int n, long accumulator) {
    // Breakpoint here - tail recursion
    if (n <= 1) {
      return accumulator;
    }
    return tailFactorialHelper(n - 1, n * accumulator);
  }

  /**
   * Binary tree traversal (demonstrates tree recursion).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in traversal method</li>
   * <li>Watch stack frames for left and right subtrees</li>
   * <li>Inspect {@code node} at each recursion level</li>
   * <li>See depth-first traversal pattern in stack</li>
   * </ul>
   */
  public void treeTraversal() {
    System.out.println("Binary tree traversal...");

    // Build simple tree
    TreeNode root = new TreeNode(1);
    root.left = new TreeNode(2);
    root.right = new TreeNode(3);
    root.left.left = new TreeNode(4);
    root.left.right = new TreeNode(5);
    root.right.left = new TreeNode(6);
    root.right.right = new TreeNode(7);

    System.out.println("Inorder traversal:");
    inorderTraversal(root);
    System.out.println();
  }

  private void inorderTraversal(TreeNode node) {
    if (node == null) {
      return;
    }

    inorderTraversal(node.left); // Breakpoint here - left subtree
    System.out.print(node.value + " "); // Process node
    inorderTraversal(node.right); // Right subtree
  }

  /**
   * Mutual recursion (even/odd check).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoints in both isEven and isOdd</li>
   * <li>Watch how they call each other</li>
   * <li>Examine stack showing alternating calls</li>
   * <li>Notice the mutual dependency pattern</li>
   * </ul>
   *
   * @param n Number to check
   * @return true if even
   */
  public boolean isEven(int n) {
    // Breakpoint here
    if (n == 0) {
      return true;
    }
    return isOdd(n - 1); // Call to isOdd
  }

  /**
   * Odd check (mutual recursion with isEven).
   *
   * @param n Number to check
   * @return true if odd
   */
  public boolean isOdd(int n) {
    // Breakpoint here
    if (n == 0) {
      return false;
    }
    return isEven(n - 1); // Call to isEven
  }

  /**
   * Stack overflow scenario (controlled).
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in method</li>
   * <li>Set conditional breakpoint: {@code depth == 1000}</li>
   * <li>Examine stack depth when it hits</li>
   * <li>See how stack grows until overflow</li>
   * <li>Notice the limit is caught before actual overflow</li>
   * </ul>
   */
  public void demonstrateStackDepth() {
    System.out.println("Demonstrating stack depth...");
    try {
      deepRecursion(0);
    } catch (StackOverflowError e) {
      System.out.println("Stack overflow at depth: " + e.getMessage());
    }
  }

  private void deepRecursion(int depth) {
    // Breakpoint with condition: depth == 1000
    if (depth > 10000) { // Safety limit to prevent actual overflow
      System.out.println("Reached depth limit: " + depth);
      return;
    }

    deepRecursion(depth + 1); // Unlimited recursion (with safety)
  }

  /**
   * Method call chain with different return types.
   *
   * <p>
   * <b>Try this:</b>
   * <ul>
   * <li>Set breakpoint in {@link #processData(String)}</li>
   * <li>Examine stack showing transformation pipeline</li>
   * <li>Inspect return values at each level</li>
   * <li>See how data transforms through the chain</li>
   * </ul>
   */
  public void dataTransformationChain() {
    System.out.println("Data transformation chain...");
    String input = "hello world";
    int result = processData(input);
    System.out.println("Result: " + result);
  }

  private int processData(String data) {
    // Breakpoint here
    String cleaned = cleanData(data);
    String transformed = transformData(cleaned);
    return analyzeData(transformed);
  }

  private String cleanData(String data) {
    System.out.println("Cleaning: " + data);
    return data.trim().toLowerCase();
  }

  private String transformData(String data) {
    System.out.println("Transforming: " + data);
    return data.replace(" ", "_");
  }

  private int analyzeData(String data) {
    System.out.println("Analyzing: " + data);
    return data.length();
  }

  /**
   * Run all call stack scenarios.
   */
  public void runAllScenarios() {
    System.out.println("\n=== Call Stack Scenarios ===\n");

    System.out.println("1. Deep Call Chain:");
    deepCallChain();

    System.out.println("\n2. Recursive Factorial:");
    long fact = recursiveFactorial(5);
    System.out.println("5! = " + fact);

    System.out.println("\n3. Recursive Fibonacci:");
    long fib = recursiveFibonacci(7);
    System.out.println("fib(7) = " + fib);

    System.out.println("\n4. Tail-Recursive Factorial:");
    long tailFact = tailRecursiveFactorial(5);
    System.out.println("5! = " + tailFact);

    System.out.println("\n5. Tree Traversal:");
    treeTraversal();

    System.out.println("\n6. Mutual Recursion:");
    boolean even = isEven(6);
    boolean odd = isOdd(7);
    System.out.println("6 is even: " + even);
    System.out.println("7 is odd: " + odd);

    System.out.println("\n7. Stack Depth:");
    demonstrateStackDepth();

    System.out.println("\n8. Data Transformation Chain:");
    dataTransformationChain();

    System.out.println("\n=== Call Stack Scenarios Complete ===\n");
  }

  // ============================================================================
  // Supporting classes
  // ============================================================================

  /**
   * Simple tree node for traversal demonstration. Getters are for debugger
   * variable inspection, not direct code usage.
   */
  @SuppressWarnings("unused") // Getters are for debugger inspection during tree traversal demos
  private static class TreeNode {
    private final int value;
    private TreeNode left;
    private TreeNode right;

    public TreeNode(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    public TreeNode getLeft() {
      return left;
    }

    public TreeNode getRight() {
      return right;
    }
  }
}
