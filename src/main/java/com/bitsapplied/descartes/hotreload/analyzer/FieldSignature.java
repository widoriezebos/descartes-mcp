package com.bitsapplied.descartes.hotreload.analyzer;

import java.util.Objects;

import org.objectweb.asm.Opcodes;

/**
 * Represents a field signature including its name, type descriptor, and
 * modifiers.
 * 
 * @author Descartes MCP
 */
public class FieldSignature {

  private final int access;
  private final String name;
  private final String descriptor;
  private final String signature;

  public FieldSignature(int access, String name, String descriptor, String signature) {
    this.access = access;
    this.name = name;
    this.descriptor = descriptor;
    this.signature = signature;
  }

  /**
   * Get a unique key for this field.
   * 
   * @return Field key (name)
   */
  public String getKey() {
    return name;
  }

  public int getAccess() {
    return access;
  }

  public String getName() {
    return name;
  }

  public String getDescriptor() {
    return descriptor;
  }

  public String getSignature() {
    return signature;
  }

  /**
   * Check if this is a synthetic field.
   * 
   * @return true if synthetic
   */
  public boolean isSynthetic() {
    return (access & Opcodes.ACC_SYNTHETIC) != 0;
  }

  /**
   * Check if this field signature is compatible with another for redefinition.
   * Fields must have the same name, type, and critical modifiers.
   * 
   * @param other Other field signature
   * @return true if compatible
   */
  public boolean isCompatibleWith(FieldSignature other) {
    if (other == null) {
      return false;
    }

    // Names must match
    if (!name.equals(other.name)) {
      return false;
    }

    // Descriptors (types) must match
    if (!descriptor.equals(other.descriptor)) {
      return false;
    }

    // Check critical access flags
    // We don't allow changes to:
    // - ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED (visibility)
    // - ACC_STATIC (instance vs static)
    // - ACC_FINAL (can affect JIT optimizations)
    // We allow changes to:
    // - ACC_VOLATILE
    // - ACC_TRANSIENT

    int criticalFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
        | Opcodes.ACC_FINAL;

    int currentCritical = access & criticalFlags;
    int otherCritical = other.access & criticalFlags;

    return currentCritical == otherCritical;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null || getClass() != obj.getClass())
      return false;

    FieldSignature that = (FieldSignature) obj;
    return access == that.access && Objects.equals(name, that.name) && Objects.equals(descriptor, that.descriptor)
        && Objects.equals(signature, that.signature);
  }

  @Override
  public int hashCode() {
    return Objects.hash(access, name, descriptor, signature);
  }

  @Override
  public String toString() {
    return String.format("Field[%s:%s, access=0x%x]", name, descriptor, access);
  }
}