package com.bitsapplied.descartes.debugger.variables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.util.ThreadUtils;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Type;

/**
 * Tests for VariableReferenceManager.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Object reference registration</li>
 * <li>Reference ID retrieval</li>
 * <li>Deduplication (same object gets same ID)</li>
 * <li>Reference validation</li>
 * <li>Clear operation</li>
 * <li>Reference removal</li>
 * <li>Thread safety</li>
 * </ul>
 */
@EnabledOnJre({ JRE.JAVA_11, JRE.JAVA_17, JRE.JAVA_21, JRE.JAVA_23, JRE.OTHER })
public class VariableReferenceManagerTest {
  private static final Logger logger = LoggerFactory.getLogger(VariableReferenceManagerTest.class);

  private VariableReferenceManager manager;

  @BeforeEach
  public void setUp() {
    manager = new VariableReferenceManager();
  }

  /**
   * Tests registering an object reference.
   */
  @Test
  public void testRegisterObjectReference() {
    logger.info("Testing register object reference...");

    ObjectReference objRef = createMockObjectReference("TestClass");

    int refId = manager.registerObjectReference(objRef);

    assertTrue(refId > 0, "Reference ID should be positive");
    assertEquals(1, refId, "First reference ID should be 1");
    assertEquals(1, manager.getReferenceCount());

    logger.info("Register object reference test passed");
  }

  /**
   * Tests registering null returns 0.
   */
  @Test
  public void testRegisterNullReturnsZero() {
    logger.info("Testing register null returns zero...");

    int refId = manager.registerObjectReference(null);

    assertEquals(0, refId, "Null object should return reference ID 0");
    assertEquals(0, manager.getReferenceCount(), "Should not count null references");

    logger.info("Register null returns zero test passed");
  }

  /**
   * Tests registering same object twice returns same ID (deduplication).
   */
  @Test
  public void testRegisterSameObjectTwice() {
    logger.info("Testing register same object twice...");

    ObjectReference objRef = createMockObjectReference("TestClass");

    int refId1 = manager.registerObjectReference(objRef);
    int refId2 = manager.registerObjectReference(objRef);

    assertEquals(refId1, refId2, "Same object should get same reference ID");
    assertEquals(1, manager.getReferenceCount(), "Should only count object once");

    logger.info("Register same object twice test passed");
  }

  /**
   * Tests registering different objects get different IDs.
   */
  @Test
  public void testRegisterDifferentObjects() {
    logger.info("Testing register different objects...");

    ObjectReference objRef1 = createMockObjectReference("Class1");
    ObjectReference objRef2 = createMockObjectReference("Class2");

    int refId1 = manager.registerObjectReference(objRef1);
    int refId2 = manager.registerObjectReference(objRef2);

    assertTrue(refId1 != refId2, "Different objects should get different IDs");
    assertEquals(2, manager.getReferenceCount());

    logger.info("Register different objects test passed");
  }

  /**
   * Tests getting object reference by ID.
   */
  @Test
  public void testGetObjectReference() {
    logger.info("Testing get object reference...");

    ObjectReference objRef = createMockObjectReference("TestClass");
    int refId = manager.registerObjectReference(objRef);

    ObjectReference retrieved = manager.getObjectReference(refId);

    assertNotNull(retrieved);
    assertEquals(objRef, retrieved);

    logger.info("Get object reference test passed");
  }

  /**
   * Tests getting object reference with invalid ID returns null.
   */
  @Test
  public void testGetObjectReferenceInvalidId() {
    logger.info("Testing get object reference invalid ID...");

    assertNull(manager.getObjectReference(0), "ID 0 should return null");
    assertNull(manager.getObjectReference(-1), "Negative ID should return null");
    assertNull(manager.getObjectReference(999), "Non-existent ID should return null");

    logger.info("Get object reference invalid ID test passed");
  }

  /**
   * Tests getting existing reference ID.
   */
  @Test
  public void testGetExistingReferenceId() {
    logger.info("Testing get existing reference ID...");

    ObjectReference objRef = createMockObjectReference("TestClass");
    int refId = manager.registerObjectReference(objRef);

    int existingId = manager.getExistingReferenceId(objRef);

    assertEquals(refId, existingId);

    logger.info("Get existing reference ID test passed");
  }

  /**
   * Tests getting existing reference ID for unregistered object returns 0.
   */
  @Test
  public void testGetExistingReferenceIdUnregistered() {
    logger.info("Testing get existing reference ID unregistered...");

    ObjectReference objRef = createMockObjectReference("TestClass");

    int existingId = manager.getExistingReferenceId(objRef);

    assertEquals(0, existingId, "Unregistered object should return 0");

    logger.info("Get existing reference ID unregistered test passed");
  }

  /**
   * Tests getting existing reference ID for null returns 0.
   */
  @Test
  public void testGetExistingReferenceIdNull() {
    logger.info("Testing get existing reference ID null...");

    int existingId = manager.getExistingReferenceId(null);

    assertEquals(0, existingId);

    logger.info("Get existing reference ID null test passed");
  }

  /**
   * Tests isValidReference.
   */
  @Test
  public void testIsValidReference() {
    logger.info("Testing is valid reference...");

    ObjectReference objRef = createMockObjectReference("TestClass");
    int refId = manager.registerObjectReference(objRef);

    assertTrue(manager.isValidReference(refId), "Registered ID should be valid");
    assertFalse(manager.isValidReference(0), "ID 0 should be invalid");
    assertFalse(manager.isValidReference(-1), "Negative ID should be invalid");
    assertFalse(manager.isValidReference(999), "Non-existent ID should be invalid");

    logger.info("Is valid reference test passed");
  }

  /**
   * Tests clear operation.
   */
  @Test
  public void testClear() {
    logger.info("Testing clear...");

    ObjectReference objRef1 = createMockObjectReference("Class1");
    ObjectReference objRef2 = createMockObjectReference("Class2");

    manager.registerObjectReference(objRef1);
    manager.registerObjectReference(objRef2);

    assertEquals(2, manager.getReferenceCount());

    manager.clear();

    assertEquals(0, manager.getReferenceCount());
    assertFalse(manager.isValidReference(1));
    assertFalse(manager.isValidReference(2));

    logger.info("Clear test passed");
  }

  /**
   * Tests clear resets ID generator.
   */
  @Test
  public void testClearResetsIdGenerator() {
    logger.info("Testing clear resets ID generator...");

    ObjectReference objRef1 = createMockObjectReference("Class1");
    int refId1 = manager.registerObjectReference(objRef1);
    assertEquals(1, refId1);

    manager.clear();

    ObjectReference objRef2 = createMockObjectReference("Class2");
    int refId2 = manager.registerObjectReference(objRef2);
    assertEquals(1, refId2, "ID should reset to 1 after clear");

    logger.info("Clear resets ID generator test passed");
  }

  /**
   * Tests remove reference.
   */
  @Test
  public void testRemoveReference() {
    logger.info("Testing remove reference...");

    ObjectReference objRef = createMockObjectReference("TestClass");
    int refId = manager.registerObjectReference(objRef);

    assertEquals(1, manager.getReferenceCount());
    assertTrue(manager.isValidReference(refId));

    manager.removeReference(refId);

    assertEquals(0, manager.getReferenceCount());
    assertFalse(manager.isValidReference(refId));
    assertNull(manager.getObjectReference(refId));

    logger.info("Remove reference test passed");
  }

  /**
   * Tests removing non-existent reference is safe (no error).
   */
  @Test
  public void testRemoveNonExistentReference() {
    logger.info("Testing remove non-existent reference...");

    // Should not throw exception
    manager.removeReference(999);

    logger.info("Remove non-existent reference test passed");
  }

  /**
   * Tests reference count accuracy.
   */
  @Test
  public void testReferenceCount() {
    logger.info("Testing reference count...");

    assertEquals(0, manager.getReferenceCount());

    manager.registerObjectReference(createMockObjectReference("Class1"));
    assertEquals(1, manager.getReferenceCount());

    manager.registerObjectReference(createMockObjectReference("Class2"));
    assertEquals(2, manager.getReferenceCount());

    manager.registerObjectReference(createMockObjectReference("Class3"));
    assertEquals(3, manager.getReferenceCount());

    logger.info("Reference count test passed");
  }

  /**
   * Tests thread safety for concurrent registrations.
   */
  @Test
  public void testThreadSafety() throws Exception {
    logger.info("Testing thread safety...");

    int threadCount = 10;
    int operationsPerThread = 100;

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);

    List<Throwable> errors = new ArrayList<>();
    AtomicInteger totalRegistrations = new AtomicInteger(0);

    for (int t = 0; t < threadCount; t++) {
      executor.submit(() -> {
        try {
          for (int i = 0; i < operationsPerThread; i++) {
            ObjectReference objRef = createMockObjectReference(
                "Thread" + ThreadUtils.getThreadId(Thread.currentThread()) + "_" + i);
            int refId = manager.registerObjectReference(objRef);

            if (refId > 0) {
              totalRegistrations.incrementAndGet();
            }

            // Verify we can retrieve it
            ObjectReference retrieved = manager.getObjectReference(refId);
            if (retrieved != objRef) {
              throw new AssertionError("Retrieved reference does not match");
            }
          }
        } catch (Throwable e) {
          errors.add(e);
        } finally {
          latch.countDown();
        }
      });
    }

    latch.await(10, TimeUnit.SECONDS);
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);

    if (!errors.isEmpty()) {
      throw new AssertionError("Thread safety violations detected: " + errors.size() + " errors", errors.get(0));
    }

    assertEquals(threadCount * operationsPerThread, totalRegistrations.get());
    assertEquals(threadCount * operationsPerThread, manager.getReferenceCount());

    logger.info("Thread safety test passed");
  }

  /**
   * Tests ID generation is sequential.
   */
  @Test
  public void testIdGenerationSequential() {
    logger.info("Testing ID generation sequential...");

    for (int i = 1; i <= 10; i++) {
      ObjectReference objRef = createMockObjectReference("Class" + i);
      int refId = manager.registerObjectReference(objRef);
      assertEquals(i, refId, "Reference IDs should be sequential");
    }

    logger.info("ID generation sequential test passed");
  }

  /**
   * Creates a mock ObjectReference for testing.
   */
  private ObjectReference createMockObjectReference(String typeName) {
    ObjectReference objRef = mock(ObjectReference.class);
    Type type = mock(Type.class);
    when(type.name()).thenReturn(typeName);
    when(objRef.type()).thenReturn(type);
    return objRef;
  }
}
