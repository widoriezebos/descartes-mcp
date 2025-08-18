package com.bitsapplied.descartes.hotreload.analyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the structure of a Java class including its methods, fields,
 * interfaces, and hierarchy.
 * 
 * @author Descartes MCP
 */
public class ClassStructure {

  private String className;
  private String superClassName;
  private int access;
  private String signature;
  private final Set<String> interfaces = new LinkedHashSet<>();
  private final Map<String, MethodSignature> methods = new LinkedHashMap<>();
  private final Map<String, FieldSignature> fields = new LinkedHashMap<>();

  public void setClassName(String className) {
    this.className = className;
  }

  public String getClassName() {
    return className;
  }

  public void setSuperClassName(String superClassName) {
    this.superClassName = superClassName;
  }

  public String getSuperClassName() {
    return superClassName;
  }

  public void setAccess(int access) {
    this.access = access;
  }

  public int getAccess() {
    return access;
  }

  public void setSignature(String signature) {
    this.signature = signature;
  }

  public String getSignature() {
    return signature;
  }

  public void addInterface(String interfaceName) {
    interfaces.add(interfaceName);
  }

  public Set<String> getInterfaces() {
    return new LinkedHashSet<>(interfaces);
  }

  public void addMethod(MethodSignature method) {
    methods.put(method.getKey(), method);
  }

  public Map<String, MethodSignature> getMethods() {
    return new LinkedHashMap<>(methods);
  }

  public void addField(FieldSignature field) {
    fields.put(field.getKey(), field);
  }

  public Map<String, FieldSignature> getFields() {
    return new LinkedHashMap<>(fields);
  }

  /**
   * Check if this structure is compatible with another for redefinition. Returns
   * a list of incompatibilities.
   * 
   * @param other Other class structure
   * @return List of incompatibility descriptions (empty if compatible)
   */
  public List<String> getIncompatibilities(ClassStructure other) {
    List<String> incompatibilities = new ArrayList<>();

    // Check if superclass changed
    if (!Objects.equals(superClassName, other.superClassName)) {
      incompatibilities.add("Superclass changed from " + superClassName + " to " + other.superClassName);
    }

    // Check if interfaces changed
    if (!interfaces.equals(other.interfaces)) {
      Set<String> added = new LinkedHashSet<>(other.interfaces);
      added.removeAll(interfaces);
      Set<String> removed = new LinkedHashSet<>(interfaces);
      removed.removeAll(other.interfaces);

      if (!added.isEmpty()) {
        incompatibilities.add("Added interfaces: " + added);
      }
      if (!removed.isEmpty()) {
        incompatibilities.add("Removed interfaces: " + removed);
      }
    }

    // Check for field changes
    Set<String> currentFieldKeys = fields.keySet();
    Set<String> otherFieldKeys = other.fields.keySet();

    if (!currentFieldKeys.equals(otherFieldKeys)) {
      Set<String> addedFields = new LinkedHashSet<>(otherFieldKeys);
      addedFields.removeAll(currentFieldKeys);
      Set<String> removedFields = new LinkedHashSet<>(currentFieldKeys);
      removedFields.removeAll(otherFieldKeys);

      if (!addedFields.isEmpty()) {
        incompatibilities.add("Added fields: " + addedFields);
      }
      if (!removedFields.isEmpty()) {
        incompatibilities.add("Removed fields: " + removedFields);
      }
    }

    // Check for field signature changes
    for (String fieldKey : currentFieldKeys) {
      if (otherFieldKeys.contains(fieldKey)) {
        FieldSignature currentField = fields.get(fieldKey);
        FieldSignature otherField = other.fields.get(fieldKey);

        if (!currentField.isCompatibleWith(otherField)) {
          incompatibilities.add("Field signature changed: " + fieldKey);
        }
      }
    }

    // Check for method signature changes
    Set<String> currentMethodKeys = methods.keySet();
    Set<String> otherMethodKeys = other.methods.keySet();

    // Check for added/removed methods
    Set<String> addedMethods = new LinkedHashSet<>(otherMethodKeys);
    addedMethods.removeAll(currentMethodKeys);
    Set<String> removedMethods = new LinkedHashSet<>(currentMethodKeys);
    removedMethods.removeAll(otherMethodKeys);

    // Filter out synthetic methods and constructors from incompatibility checks
    addedMethods.removeIf(key -> {
      MethodSignature method = other.methods.get(key);
      return method.isSynthetic() || method.isBridge();
    });

    removedMethods.removeIf(key -> {
      MethodSignature method = methods.get(key);
      return method.isSynthetic() || method.isBridge();
    });

    if (!addedMethods.isEmpty()) {
      incompatibilities.add("Added methods: " + addedMethods);
    }
    if (!removedMethods.isEmpty()) {
      incompatibilities.add("Removed methods: " + removedMethods);
    }

    // Check for method signature compatibility
    for (String methodKey : currentMethodKeys) {
      if (otherMethodKeys.contains(methodKey)) {
        MethodSignature currentMethod = methods.get(methodKey);
        MethodSignature otherMethod = other.methods.get(methodKey);

        if (!currentMethod.isCompatibleWith(otherMethod)) {
          incompatibilities.add("Method incompatible: " + methodKey);
        }
      }
    }

    return incompatibilities;
  }

  @Override
  public String toString() {
    return String.format("ClassStructure[%s extends %s implements %s, methods=%d, fields=%d]", className,
        superClassName, interfaces, methods.size(), fields.size());
  }
}