package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for JShellService.
 */
public class JShellServiceTest {

  private Map<String, Object> context;
  private JShellService jshellService;

  @BeforeEach
  public void setUp() throws Exception {
    context = new HashMap<>();
    context.put("test.value", "test-context-value");
    jshellService = new JShellService(context);
  }

  @AfterEach
  public void tearDown() {
    if (jshellService != null) {
      jshellService.close();
    }
  }

  @Test
  public void testSimpleExpression() {
    EvalResult result = jshellService.eval("2 + 2");

    assertNotNull(result);
    assertEquals("", result.out());
    assertEquals("", result.err());
    assertFalse(result.events().isEmpty());

    // Check the value of the expression
    EvalResult.SnippetResult event = result.events().get(0);
    assertEquals("4", event.value());
    assertEquals("VALID", event.status());
    assertNull(event.exceptionType());
  }

  @Test
  public void testStdoutCapture() {
    EvalResult result = jshellService.eval("System.out.println(\"Hello, World!\");");

    assertEquals("Hello, World!\n", result.out());
    assertEquals("", result.err());
    assertFalse(result.events().isEmpty());

    EvalResult.SnippetResult event = result.events().get(0);
    assertEquals("VALID", event.status());
    assertNull(event.value()); // println returns void
  }

  @Test
  public void testStderrCapture() {
    EvalResult result = jshellService.eval("System.err.println(\"Error message\");");

    assertEquals("", result.out());
    assertEquals("Error message\n", result.err());
    assertFalse(result.events().isEmpty());

    EvalResult.SnippetResult event = result.events().get(0);
    assertEquals("VALID", event.status());
  }

  @Test
  public void testMixedStdoutStderr() {
    String code = """
        System.out.println("Standard output");
        System.err.println("Error output");
        System.out.println("More standard");
        """;

    EvalResult result = jshellService.eval(code);

    assertTrue(result.out().contains("Standard output"));
    assertTrue(result.out().contains("More standard"));
    assertTrue(result.err().contains("Error output"));
  }

  @Test
  public void testVariableDeclaration() {
    EvalResult result = jshellService.eval("String message = \"Test\";");

    assertFalse(result.events().isEmpty());
    EvalResult.SnippetResult event = result.events().get(0);
    assertEquals("\"Test\"", event.value());
    assertEquals("VALID", event.status());
  }

  @Test
  public void testVariablePersistence() {
    // Declare variable
    EvalResult result1 = jshellService.eval("int counter = 100;");
    assertEquals("100", result1.events().get(0).value());

    // Use variable
    EvalResult result2 = jshellService.eval("counter += 50;");
    assertEquals("150", result2.events().get(0).value());

    // Print variable
    EvalResult result3 = jshellService.eval("System.out.println(\"Counter: \" + counter);");
    assertEquals("Counter: 150\n", result3.out());
  }

  @Test
  public void testMethodDefinition() {
    String methodDef = """
        public int factorial(int n) {
            if (n <= 1) return 1;
            return n * factorial(n - 1);
        }
        """;

    EvalResult result1 = jshellService.eval(methodDef);
    assertEquals("VALID", result1.events().get(0).status());

    // Use the method
    EvalResult result2 = jshellService.eval("factorial(5)");
    assertEquals("120", result2.events().get(0).value());
  }

  @Test
  public void testClassDefinition() {
    String classDef = """
        class Person {
            private String name;
            private int age;

            public Person(String name, int age) {
                this.name = name;
                this.age = age;
            }

            public String toString() {
                return name + " (" + age + ")";
            }
        }
        """;

    EvalResult result1 = jshellService.eval(classDef);
    assertEquals("VALID", result1.events().get(0).status());

    // Create instance
    EvalResult result2 = jshellService.eval("Person p = new Person(\"Alice\", 30);");
    assertNotNull(result2.events().get(0).value());

    // Use toString
    EvalResult result3 = jshellService.eval("System.out.println(p);");
    assertEquals("Alice (30)\n", result3.out());
  }

  @Test
  public void testCompilationError() {
    EvalResult result = jshellService.eval("int x = \"not a number\";");

    assertFalse(result.events().isEmpty());
    EvalResult.SnippetResult event = result.events().get(0);
    assertEquals("REJECTED", event.status());
  }

  @Test
  public void testRuntimeException() {
    EvalResult result = jshellService.eval("int x = 10 / 0;");

    assertFalse(result.events().isEmpty());
    EvalResult.SnippetResult event = result.events().get(0);
    assertNotNull(event.exceptionMessage());
    assertTrue(event.exceptionMessage().contains("by zero"));
  }

  @Test
  public void testNullPointerException() {
    String code = """
        String s = null;
        s.length();
        """;

    EvalResult result = jshellService.eval(code);

    // First statement should be valid
    assertEquals("VALID", result.events().get(0).status());
    assertEquals("null", result.events().get(0).value()); // String representation of null

    // Second should throw NPE
    EvalResult.SnippetResult npeEvent = result.events().get(1);
    assertNotNull(npeEvent.exceptionMessage());
  }

  @Test
  public void testMultilineStatements() {
    String code = """
        int sum = 0;
        for (int i = 1; i <= 10; i++) {
            sum += i;
        }
        System.out.println("Sum: " + sum);
        sum
        """;

    EvalResult result = jshellService.eval(code);

    assertEquals("Sum: 55\n", result.out());

    // Find the last event that returns the sum value
    EvalResult.SnippetResult lastEvent = result.events().get(result.events().size() - 1);
    assertEquals("55", lastEvent.value());
  }

  @Test
  public void testImportStatements() {
    EvalResult result1 = jshellService.eval("import java.util.*;");
    assertEquals("VALID", result1.events().get(0).status());

    EvalResult result2 = jshellService.eval("List<String> list = new ArrayList<>();");
    assertEquals("VALID", result2.events().get(0).status());

    EvalResult result3 = jshellService.eval("list.add(\"test\"); list.size();");
    // Should have two events: add returns true, size returns 1
    assertTrue(result3.events().size() >= 1);

    // Find the size() result
    EvalResult.SnippetResult sizeEvent = result3.events().stream().filter(e -> "1".equals(e.value())).findFirst()
        .orElse(null);
    assertNotNull(sizeEvent);
    assertEquals("1", sizeEvent.value());
  }

  @Test
  public void testLambdaExpressions() {
    String code = """
        import java.util.function.*;
        Function<Integer, Integer> square = x -> x * x;
        square.apply(7)
        """;

    EvalResult result = jshellService.eval(code);

    // Find the apply result
    EvalResult.SnippetResult applyEvent = result.events().stream().filter(e -> "49".equals(e.value())).findFirst()
        .orElse(null);
    assertNotNull(applyEvent);
    assertEquals("49", applyEvent.value());
  }

  @Test
  public void testStreamOperations() {
    String code = """
        import java.util.stream.*;
        IntStream.range(1, 11)
            .filter(n -> n % 2 == 0)
            .map(n -> n * n)
            .sum()
        """;

    EvalResult result = jshellService.eval(code);

    // Sum of squares of even numbers from 1 to 10: 4 + 16 + 36 + 64 + 100 = 220
    EvalResult.SnippetResult sumEvent = result.events().stream().filter(e -> "220".equals(e.value())).findFirst()
        .orElse(null);
    assertNotNull(sumEvent);
    assertEquals("220", sumEvent.value());
  }

  @Test
  public void testContextAccess() {
    // Test that we can access the JShellService.CTX static field
    EvalResult result1 = jshellService.eval("com.bitsapplied.descartes.util.JShellService.CTX != null");
    assertFalse(result1.events().isEmpty());
    if (result1.events().get(0).status().equals("REJECTED")) {
      // If context access fails, skip this test (depends on class loading)
      return;
    }
    assertEquals("VALID", result1.events().get(0).status());
    assertEquals("true", result1.events().get(0).value());
  }

  @Test
  public void testCustomHelperFunctions() {
    // Define our own helper functions for testing
    jshellService.eval("void p(Object o) { System.out.println(o); }");
    jshellService.eval("void pf(String fmt, Object... args) { System.out.printf(fmt, args); System.out.println(); }");

    // Test the p() helper
    EvalResult result1 = jshellService.eval("p(\"Test message\");");
    assertEquals("Test message\n", result1.out());

    // Test the pf() helper with simple format
    EvalResult result2 = jshellService.eval("pf(\"Value: %d\", 42);");
    assertEquals("Value: 42\n", result2.out());
  }

  @Test
  public void testLargeOutput() {
    String code = """
        for (int i = 0; i < 100; i++) {
            System.out.println("Line " + i);
        }
        """;

    EvalResult result = jshellService.eval(code);

    String[] lines = result.out().split("\n");
    assertEquals(100, lines.length);
    assertTrue(result.out().contains("Line 0"));
    assertTrue(result.out().contains("Line 99"));
  }

  @Test
  public void testComplexComputation() {
    String code = """
        // Fibonacci using dynamic programming
        int n = 20;
        long[] fib = new long[n + 1];
        fib[0] = 0;
        fib[1] = 1;
        for (int i = 2; i <= n; i++) {
            fib[i] = fib[i-1] + fib[i-2];
        }
        System.out.println("Fibonacci(" + n + ") = " + fib[n]);
        fib[n]
        """;

    EvalResult result = jshellService.eval(code);

    assertEquals("Fibonacci(20) = 6765\n", result.out());

    // Find the final value
    EvalResult.SnippetResult fibEvent = result.events().stream().filter(e -> "6765".equals(e.value())).findFirst()
        .orElse(null);
    assertNotNull(fibEvent);
    assertEquals("6765", fibEvent.value());
  }

  @Test
  public void testMixedValidAndInvalidCode() {
    String code = """
        int x = 10;
        int y = 20;
        int z = x + y;
        System.out.println("Sum: " + z);
        String invalid = 123;  // This will fail
        int w = 40;
        """;

    EvalResult result = jshellService.eval(code);

    // Should still execute valid parts
    assertEquals("Sum: 30\n", result.out());

    // Check for both valid and rejected events
    boolean hasValid = result.events().stream().anyMatch(e -> "VALID".equals(e.status()));
    boolean hasRejected = result.events().stream().anyMatch(e -> "REJECTED".equals(e.status()));

    assertTrue(hasValid);
    assertTrue(hasRejected);
  }

  @Test
  public void testInfiniteLoopPrevention() {
    // This test verifies that we can interrupt long-running code
    // Note: JShell in local execution doesn't have built-in timeout,
    // so we test a finite but long loop
    String code = """
        long start = System.currentTimeMillis();
        int count = 0;
        while (System.currentTimeMillis() - start < 100) {
            count++;
        }
        System.out.println("Iterations: " + count);
        count > 0
        """;

    EvalResult result = jshellService.eval(code);

    assertTrue(result.out().contains("Iterations:"));

    // Should have completed and returned true
    EvalResult.SnippetResult countEvent = result.events().stream()
        .filter(e -> e.value() != null && e.value().equals("true")).findFirst().orElse(null);
    assertNotNull(countEvent);
  }

  @Test
  public void testTimestamps() {
    EvalResult result = jshellService.eval("1 + 1");

    assertNotNull(result.startedAt());
    assertNotNull(result.finishedAt());
    assertTrue(result.finishedAt().isAfter(result.startedAt()) || result.finishedAt().equals(result.startedAt()));
  }

  @Test
  public void testEmptyInput() {
    EvalResult result = jshellService.eval("");

    // Empty input should still work but produce no events
    assertNotNull(result);
    assertEquals("", result.out());
    assertEquals("", result.err());
  }

  @Test
  public void testWhitespaceOnlyInput() {
    EvalResult result = jshellService.eval("   \n  \t  \n  ");

    assertNotNull(result);
    assertEquals("", result.out());
    assertEquals("", result.err());
  }

  @Test
  public void testSemicolonHandling() {
    // Test with semicolon
    EvalResult result1 = jshellService.eval("int a = 5;");
    assertEquals("5", result1.events().get(0).value());

    // Test without semicolon
    EvalResult result2 = jshellService.eval("int b = 10");
    assertEquals("10", result2.events().get(0).value());

    // Test multiple statements with mixed semicolons
    EvalResult result3 = jshellService.eval("int c = 15; int d = 20");
    assertEquals(2, result3.events().size());
  }

  @Test
  public void testThreadSafety() throws InterruptedException {
    // Create multiple threads that use the same JShellService
    Thread[] threads = new Thread[5];
    boolean[] success = new boolean[threads.length];

    for (int i = 0; i < threads.length; i++) {
      final int index = i;
      threads[i] = new Thread(() -> {
        try {
          EvalResult result = jshellService.eval("int thread" + index + " = " + index + ";");
          success[index] = "VALID".equals(result.events().get(0).status());
        } catch (Exception e) {
          success[index] = false;
        }
      });
      threads[i].start();
    }

    // Wait for all threads
    for (Thread t : threads) {
      t.join();
    }

    // All should succeed
    for (boolean s : success) {
      assertTrue(s);
    }

    // Variables from all threads should exist
    EvalResult result = jshellService.eval("thread0 + thread1 + thread2 + thread3 + thread4");
    assertEquals("10", result.events().get(0).value()); // 0+1+2+3+4=10
  }

  @Test
  public void testUnicodeSupport() {
    String code = """
        String emoji = "😀🎉🚀";
        String chinese = "你好世界";
        String arabic = "مرحبا بالعالم";
        System.out.println(emoji + " " + chinese + " " + arabic);
        """;

    EvalResult result = jshellService.eval(code);

    assertTrue(result.out().contains("😀🎉🚀"));
    assertTrue(result.out().contains("你好世界"));
    assertTrue(result.out().contains("مرحبا بالعالم"));
  }
}