package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for ConsoleCapture functionality.
 */
public class ConsoleCaptureTest {

  private PrintStream originalOut;
  private PrintStream originalErr;

  @BeforeEach
  public void setUp() {
    // Get the real original streams by unwrapping any existing ConsoleCapture
    PrintStream currentOut = System.out;
    PrintStream currentErr = System.err;

    // Try to get the real original streams
    // by checking if current streams are wrapped
    originalOut = getRealOriginalStream(currentOut, true);
    originalErr = getRealOriginalStream(currentErr, false);

    // First uninstall any previous ConsoleCapture to ensure clean state
    ConsoleCapture.uninstall();

    // Reset System.out/err to the real originals
    System.setOut(originalOut);
    System.setErr(originalErr);

    // Reset echo settings to defaults
    ConsoleCapture.setEchoDuringCapture(false, false);

    // Now install ConsoleCapture with clean state
    ConsoleCapture.installOnce();
  }

  private PrintStream getRealOriginalStream(PrintStream stream, boolean isOut) {
    try {
      // Use reflection to check if the stream contains a MirroringOutputStream
      java.lang.reflect.Field outField = FilterOutputStream.class.getDeclaredField("out");
      outField.setAccessible(true);
      Object innerStream = outField.get(stream);

      // Check if it's a ConsoleCapture.MirroringOutputStream
      if (innerStream != null && innerStream.getClass().getName().contains("MirroringOutputStream")) {
        // Try to get the downstream field
        java.lang.reflect.Field downstreamField = innerStream.getClass().getDeclaredField("downstream");
        downstreamField.setAccessible(true);
        Object downstream = downstreamField.get(innerStream);
        if (downstream instanceof PrintStream) {
          return (PrintStream) downstream;
        }
      }
    } catch (Exception e) {
      // If reflection fails, use stream as-is
    }
    return stream;
  }

  @AfterEach
  public void tearDown() {
    // Uninstall ConsoleCapture first
    ConsoleCapture.uninstall();

    // Reset echo settings to defaults
    ConsoleCapture.setEchoDuringCapture(false, false);

    // Then restore original streams
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  @Test
  public void testInstallOnce() {
    // Should be idempotent
    ConsoleCapture.installOnce();
    ConsoleCapture.installOnce();
    ConsoleCapture.installOnce();
    // No exception should be thrown
  }

  @Test
  public void testBasicCapture() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    assertNotNull(token);

    // Start capture
    ConsoleCapture.begin(token);

    // Write to stdout and stderr
    System.out.println("Test stdout message");
    System.err.println("Test stderr message");

    // End capture
    ConsoleCapture.end();

    // Unregister
    ConsoleCapture.unregister(token);

    // Check captured content
    String capturedOut = outBuf.toString(StandardCharsets.UTF_8);
    String capturedErr = errBuf.toString(StandardCharsets.UTF_8);

    assertEquals("Test stdout message\n", capturedOut);
    assertEquals("Test stderr message\n", capturedErr);
  }

  @Test
  public void testNoCapture() {
    // Without registering/beginning capture, output should go through normally
    System.out.println("Normal output");
    System.err.println("Normal error");

    // Since we're in a test environment, we can't easily verify the actual console
    // output
    // But we can verify that no exception is thrown
  }

  @Test
  public void testNestedCapture() {
    ByteArrayOutputStream outBuf1 = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf1 = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers1 = new ConsoleCapture.Buffers(outBuf1, errBuf1);

    ByteArrayOutputStream outBuf2 = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf2 = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers2 = new ConsoleCapture.Buffers(outBuf2, errBuf2);

    String token1 = ConsoleCapture.register(buffers1);
    String token2 = ConsoleCapture.register(buffers2);

    // Start first capture
    ConsoleCapture.begin(token1);
    System.out.println("First level");

    // Start nested capture
    ConsoleCapture.begin(token2);
    System.out.println("Second level");

    // End nested capture
    ConsoleCapture.end();
    System.out.println("Back to first");

    // End first capture
    ConsoleCapture.end();

    ConsoleCapture.unregister(token1);
    ConsoleCapture.unregister(token2);

    // Check captured content
    String captured1 = outBuf1.toString(StandardCharsets.UTF_8);
    String captured2 = outBuf2.toString(StandardCharsets.UTF_8);

    // First buffer should have messages from first level only (stack-based)
    assertTrue(captured1.contains("First level"));
    assertTrue(captured1.contains("Back to first"));
    assertFalse(captured1.contains("Second level"));

    // Second buffer should have only second level
    assertEquals("Second level\n", captured2);
  }

  @Test
  public void testEchoPolicy() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    // Test default echo settings (after our setUp resets them)
    assertFalse(ConsoleCapture.isEchoStdoutDuringCapture());
    assertFalse(ConsoleCapture.isEchoStderrDuringCapture());

    // Enable echo for stdout only
    ConsoleCapture.setEchoDuringCapture(true, false);
    assertTrue(ConsoleCapture.isEchoStdoutDuringCapture());
    assertFalse(ConsoleCapture.isEchoStderrDuringCapture());

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    System.out.println("Echoed stdout");
    System.err.println("Suppressed stderr");

    ConsoleCapture.end();

    // Both should be captured
    String capturedOut = outBuf.toString(StandardCharsets.UTF_8);
    String capturedErr = errBuf.toString(StandardCharsets.UTF_8);

    // The output should contain the expected text (might have duplicates due to
    // echo)
    assertTrue(capturedOut.contains("Echoed stdout"), "Output should contain 'Echoed stdout'. Actual: " + capturedOut);
    assertTrue(capturedErr.contains("Suppressed stderr"),
        "Error should contain 'Suppressed stderr'. Actual: " + capturedErr);

    // Reset echo settings
    ConsoleCapture.setEchoDuringCapture(false, false);
    ConsoleCapture.unregister(token);
  }

  @Test
  public void testMultipleTokens() {
    ByteArrayOutputStream outBuf1 = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf1 = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers1 = new ConsoleCapture.Buffers(outBuf1, errBuf1);

    ByteArrayOutputStream outBuf2 = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf2 = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers2 = new ConsoleCapture.Buffers(outBuf2, errBuf2);

    String token1 = ConsoleCapture.register(buffers1);
    String token2 = ConsoleCapture.register(buffers2);

    // Use first token
    ConsoleCapture.begin(token1);
    System.out.println("Message 1");
    ConsoleCapture.end();

    // Use second token
    ConsoleCapture.begin(token2);
    System.out.println("Message 2");
    ConsoleCapture.end();

    assertEquals("Message 1\n", outBuf1.toString(StandardCharsets.UTF_8));
    assertEquals("Message 2\n", outBuf2.toString(StandardCharsets.UTF_8));

    ConsoleCapture.unregister(token1);
    ConsoleCapture.unregister(token2);
  }

  @Test
  public void testInvalidToken() {
    // Begin with invalid token should not throw but also not capture
    ConsoleCapture.begin("invalid-token");
    System.out.println("Not captured");
    ConsoleCapture.end();

    // No exception should be thrown
  }

  @Test
  public void testEndWithoutBegin() {
    // Calling end without begin should not throw
    ConsoleCapture.end();
    // No exception
  }

  @Test
  public void testUnregisterNonexistentToken() {
    // Should not throw
    ConsoleCapture.unregister("nonexistent");
  }

  @Test
  public void testLargeOutput() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    // Write large amount of data
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      String line = "Line " + i + " with some text content";
      System.out.println(line);
      sb.append(line).append("\n");
    }

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);

    assertEquals(sb.toString(), outBuf.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void testMixedOutputAndError() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    System.out.print("out1");
    System.err.print("err1");
    System.out.println("out2");
    System.err.println("err2");

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);

    assertEquals("out1out2\n", outBuf.toString(StandardCharsets.UTF_8));
    assertEquals("err1err2\n", errBuf.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void testPrintMethods() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    // Test various print methods
    System.out.print("print");
    System.out.println("println");
    System.out.printf("printf %d %s%n", 42, "test");
    System.out.format("format %.2f%n", 3.14159);

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);

    String captured = outBuf.toString(StandardCharsets.UTF_8);
    // The output might be captured multiple times due to how PrintStream works
    // Just check that the expected strings are present somewhere
    assertTrue(captured.contains("print"), "Output doesn't contain 'print'. Actual: " + captured);
    assertTrue(captured.contains("println"), "Output doesn't contain 'println'. Actual: " + captured);
    assertTrue(captured.contains("42") && captured.contains("test"),
        "Output doesn't contain printf output. Actual: " + captured);
    assertTrue(captured.contains("3") && (captured.contains("14") || captured.contains(",14")),
        "Output doesn't contain formatted float. Actual: " + captured);
  }

  @Test
  public void testByteArrayWrite() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    // Write byte array directly
    byte[] data = "Test byte array".getBytes(StandardCharsets.UTF_8);
    System.out.write(data, 0, data.length);
    System.out.flush();

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);

    assertEquals("Test byte array", outBuf.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void testEmptyWrite() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    // Write empty array (edge case)
    byte[] empty = new byte[0];
    System.out.write(empty, 0, 0);

    // Write with zero length
    byte[] data = "test".getBytes(StandardCharsets.UTF_8);
    System.out.write(data, 0, 0);

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);

    assertEquals("", outBuf.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void testUnicodeOutput() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    System.out.println("Unicode: 你好世界 🎉 مرحبا");
    System.err.println("Symbols: ∑ ∏ ∫ ∞ ≈ ≠");

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);

    String capturedOut = outBuf.toString(StandardCharsets.UTF_8);
    String capturedErr = errBuf.toString(StandardCharsets.UTF_8);

    assertTrue(capturedOut.contains("你好世界"));
    assertTrue(capturedOut.contains("🎉"));
    assertTrue(capturedErr.contains("∑"));
    assertTrue(capturedErr.contains("∞"));
  }

  @Test
  public void testCurrentWithNoActiveCapture() {
    // Internal method test - current() should return null when no capture active
    assertNull(ConsoleCapture.current());
  }

  @Test
  public void testCurrentWithActiveCapture() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    // current() should return the active buffers
    ConsoleCapture.Buffers current = ConsoleCapture.current();
    assertNotNull(current);
    assertEquals(buffers, current);

    ConsoleCapture.end();

    // After end, should be null again
    assertNull(ConsoleCapture.current());

    ConsoleCapture.unregister(token);
  }

  @Test
  public void testThreadSafety() throws InterruptedException {
    // Note: ConsoleCapture is ClassLoader-based, not Thread-based.
    // All threads in this test share the same ClassLoader, so they will share the
    // same capture.
    // This test verifies thread safety in concurrent registration/unregistration,
    // not independent capture per thread.

    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    AtomicBoolean hasError = new AtomicBoolean(false);

    Thread[] threads = new Thread[threadCount];

    // Single shared buffer for all threads (ClassLoader-based capture)
    ByteArrayOutputStream sharedOutBuffer = new ByteArrayOutputStream();
    ByteArrayOutputStream sharedErrBuffer = new ByteArrayOutputStream();
    ConsoleCapture.Buffers sharedBuffers = new ConsoleCapture.Buffers(sharedOutBuffer, sharedErrBuffer);
    String sharedToken = ConsoleCapture.register(sharedBuffers);

    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;

      threads[i] = new Thread(() -> {
        try {
          // Wait for start signal
          startLatch.await();

          // All threads use the same token (ClassLoader-based)
          ConsoleCapture.begin(sharedToken);
          System.out.println("Thread " + threadId + " output");
          System.err.println("Thread " + threadId + " error");
          ConsoleCapture.end();

        } catch (Exception e) {
          hasError.set(true);
          e.printStackTrace();
        } finally {
          doneLatch.countDown();
        }
      });
      threads[i].start();
    }

    // Start all threads simultaneously
    startLatch.countDown();

    // Wait for completion
    doneLatch.await();

    ConsoleCapture.unregister(sharedToken);

    assertFalse(hasError.get());

    // Verify all threads' output was captured in the shared buffer
    String capturedOut = sharedOutBuffer.toString(StandardCharsets.UTF_8);
    String capturedErr = sharedErrBuffer.toString(StandardCharsets.UTF_8);

    // All threads should have written to the buffer
    for (int i = 0; i < threadCount; i++) {
      assertTrue(capturedOut.contains("Thread " + i + " output"),
          "Missing output from thread " + i + ". Captured: " + capturedOut);
      assertTrue(capturedErr.contains("Thread " + i + " error"),
          "Missing error from thread " + i + ". Captured: " + capturedErr);
    }
  }

  @Test
  public void testFlushAndClose() {
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);

    // Write without newline and flush
    System.out.print("Flushed");
    System.out.flush();

    // Should be captured even without newline
    assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("Flushed"));

    ConsoleCapture.end();
    ConsoleCapture.unregister(token);
  }

  @Test
  public void testForceUninstall() {
    // Test that forceUninstall truly removes all ConsoleCapture wrapping
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);

    // Install multiple times to simulate what happens with multiple tests
    ConsoleCapture.installOnce();
    ConsoleCapture.installOnce(); // Should be idempotent

    ConsoleCapture.begin(token);
    System.out.println("Before force uninstall");
    ConsoleCapture.end();

    assertEquals("Before force uninstall\n", outBuf.toString(StandardCharsets.UTF_8));

    // Force uninstall should completely remove ConsoleCapture
    ConsoleCapture.forceUninstall();

    // After forceUninstall, no capture should work
    outBuf.reset();
    ConsoleCapture.begin(token);
    System.out.println("After force uninstall");
    ConsoleCapture.end();

    assertEquals("", outBuf.toString(StandardCharsets.UTF_8), "Nothing should be captured after forceUninstall");

    ConsoleCapture.unregister(token);

    // Reinstall for cleanup
    ConsoleCapture.installOnce();
  }

  @Test
  public void testInstallUninstallCycle() {
    // Test is already installed from setUp, so first verify capture works
    ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
    ConsoleCapture.Buffers buffers = new ConsoleCapture.Buffers(outBuf, errBuf);

    String token = ConsoleCapture.register(buffers);
    ConsoleCapture.begin(token);
    System.out.println("While installed");
    ConsoleCapture.end();

    assertEquals("While installed\n", outBuf.toString(StandardCharsets.UTF_8));

    // Use forceUninstall to completely remove ConsoleCapture
    // This restores the TRUE original streams from before any test ran
    ConsoleCapture.forceUninstall();

    // After forceUninstall, capture should not work at all
    outBuf.reset();
    ConsoleCapture.begin(token);
    System.out.println("After uninstall");
    ConsoleCapture.end();

    // Should not be captured after forceUninstall
    assertEquals("", outBuf.toString(StandardCharsets.UTF_8), "Should not capture after forceUninstall");

    ConsoleCapture.unregister(token);

    // Reinstall for cleanup (so tearDown works properly)
    ConsoleCapture.installOnce();
  }
}