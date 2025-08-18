package com.bitsapplied.descartes.hotreload.analyzer;

import java.util.Arrays;
import java.util.Objects;

import org.objectweb.asm.Opcodes;

/**
 * Represents a method signature including its name, descriptor, and modifiers.
 * 
 * @author Descartes MCP
 */
public class MethodSignature {

  private final int access;
  private final String name;
  private final String descriptor;
  private final String signature;
  private final String[] exceptions;

  public MethodSignature(int access, String name, String descriptor, String signature, String[] exceptions) {
    this.access = access;
    this.name = name;
    this.descriptor = descriptor;
    this.signature = signature;
    this.exceptions = exceptions != null ? exceptions.clone() : new String[0];
  }

  /**
   * Get a unique key for this method.
   * 
   * @return Method key (name + descriptor)
   */
  public String getKey() {
    return name + descriptor;
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

  public String[] getExceptions() {
    return exceptions.clone();
  }

  /**
   * Check if this is a synthetic method.
   * 
   * @return true if synthetic
   */
  public boolean isSynthetic() {
    return (access & Opcodes.ACC_SYNTHETIC) != 0;
  }

  /**
   * Check if this is a bridge method.
   * 
   * @return true if bridge method
   */
  public boolean isBridge() {
    return (access & Opcodes.ACC_BRIDGE) != 0;
  }

  /**
   * Check if this is a constructor.
   * 
   * @return true if constructor
   */
  public boolean isConstructor() {
    return "<init>".equals(name);
  }

  /**
   * Check if this is a static initializer.
   * 
   * @return true if static initializer
   */
  public boolean isStaticInitializer() {
    return "<clinit>".equals(name);
  }

  /**
   * Check if this method signature is compatible with another for redefinition.
   * Methods are compatible if they have the same descriptor and compatible
   * modifiers.
   * 
   * @param other Other method signature
   * @return true if compatible
   */
  public boolean isCompatibleWith(MethodSignature other) {
    if (other == null) {
      return false;
    }

    // Names and descriptors must match exactly
    if (!name.equals(other.name) || !descriptor.equals(other.descriptor)) {
      return false;
    }

    // Check if critical access flags changed
    // We allow changes to:
    // - ACC_SYNCHRONIZED
    // - ACC_NATIVE (cannot be changed at runtime, will fail during redefinition)
    // - ACC_STRICT
    // We don't allow changes to:
    // - ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED (visibility)
    // - ACC_STATIC (instance vs static)
    // - ACC_FINAL (can be problematic)
    // - ACC_ABSTRACT (would change class contract)

    int criticalFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
        | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT;

    int currentCritical = access & criticalFlags;
    int otherCritical = other.access & criticalFlags;

    if (currentCritical != otherCritical) {
      return false;
    }

    // Check if exceptions are compatible (order doesn't matter for compatibility)
    // Note: Exception changes are generally allowed in redefinition

    return true;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null || getClass() != obj.getClass())
      return false;

    MethodSignature that = (MethodSignature) obj;
    return access == that.access && Objects.equals(name, that.name) && Objects.equals(descriptor, that.descriptor)
        && Objects.equals(signature, that.signature) && Arrays.equals(exceptions, that.exceptions);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(access, name, descriptor, signature);
    result = 31 * result + Arrays.hashCode(exceptions);
    return result;
  }

  @Override
  public String toString() {
    return String.format("Method[%s%s, access=0x%x]", name, descriptor, access);
  }
}