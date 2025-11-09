package com.bitsapplied.descartes.debugger.variables;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jdi.ObjectReference;

/**
 * Manages variable references for lazy loading of object properties.
 *
 * <p>
 * Variable references enable efficient debugging by:
 * <ul>
 * <li>Assigning unique IDs to object references</li>
 * <li>Allowing lazy loading of object properties</li>
 * <li>Avoiding circular reference issues</li>
 * <li>Supporting hierarchical variable expansion</li>
 * </ul>
 *
 * <p>
 * Reference ID Convention:
 * <ul>
 * <li>0 = not expandable (primitives, null)</li>
 * <li>&gt;0 = expandable object with lazy-loaded properties</li>
 * </ul>
 *
 * <p>
 * Thread Safety: This class is thread-safe using ConcurrentHashMap.
 */
public class VariableReferenceManager {
  private static final Logger logger = LoggerFactory.getLogger(VariableReferenceManager.class);

  // ID generator (starts at 1, 0 is reserved for non-expandable)
  private final AtomicInteger nextReferenceId = new AtomicInteger(1);

  // Reference ID → ObjectReference mapping
  private final Map<Integer, ObjectReference> referenceMap = new ConcurrentHashMap<>();

  // ObjectReference → Reference ID reverse mapping (for deduplication)
  private final Map<ObjectReference, Integer> reverseMap = new ConcurrentHashMap<>();

  /**
   * Registers an object reference and returns its variable reference ID.
   *
   * <p>
   * If the object is already registered, returns the existing ID.
   *
   * @param objectRef the object reference
   * @return the variable reference ID (always &gt; 0)
   */
  public int registerObjectReference(ObjectReference objectRef) {
    if (objectRef == null) {
      return 0;
    }

    // Check if already registered
    Integer existingId = reverseMap.get(objectRef);
    if (existingId != null) {
      logger.trace("Object already registered with ID: {}", existingId);
      return existingId;
    }

    // Generate new ID
    int referenceId = nextReferenceId.getAndIncrement();

    // Store both mappings
    referenceMap.put(referenceId, objectRef);
    reverseMap.put(objectRef, referenceId);

    logger.trace("Registered object {} with reference ID: {}", objectRef.type().name(), referenceId);

    return referenceId;
  }

  /**
   * Gets the object reference for a given variable reference ID.
   *
   * @param referenceId the variable reference ID
   * @return the object reference, or null if not found
   */
  public ObjectReference getObjectReference(int referenceId) {
    if (referenceId <= 0) {
      return null;
    }

    ObjectReference objRef = referenceMap.get(referenceId);

    if (objRef == null) {
      logger.warn("No object found for reference ID: {}", referenceId);
    }

    return objRef;
  }

  /**
   * Gets the variable reference ID for an object (if already registered).
   *
   * @param objectRef the object reference
   * @return the reference ID, or 0 if not registered
   */
  public int getExistingReferenceId(ObjectReference objectRef) {
    if (objectRef == null) {
      return 0;
    }

    Integer id = reverseMap.get(objectRef);
    return id != null ? id : 0;
  }

  /**
   * Checks if a variable reference ID is valid and registered.
   *
   * @param referenceId the variable reference ID
   * @return true if valid and registered
   */
  public boolean isValidReference(int referenceId) {
    return referenceId > 0 && referenceMap.containsKey(referenceId);
  }

  /**
   * Clears all registered references.
   *
   * <p>
   * This should be called when a debug session ends or when variables become
   * invalid (e.g., after stepping or resuming).
   */
  public void clear() {
    int count = referenceMap.size();
    referenceMap.clear();
    reverseMap.clear();
    nextReferenceId.set(1);
    logger.debug("Cleared {} variable references", count);
  }

  /**
   * Gets the total number of registered references.
   *
   * @return reference count
   */
  public int getReferenceCount() {
    return referenceMap.size();
  }

  /**
   * Removes a specific variable reference.
   *
   * @param referenceId the reference ID to remove
   */
  public void removeReference(int referenceId) {
    ObjectReference objRef = referenceMap.remove(referenceId);
    if (objRef != null) {
      reverseMap.remove(objRef);
      logger.trace("Removed variable reference: {}", referenceId);
    }
  }
}
