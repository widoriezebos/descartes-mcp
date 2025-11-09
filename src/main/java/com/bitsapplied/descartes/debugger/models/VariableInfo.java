package com.bitsapplied.descartes.debugger.models;

/**
 * Variable information record.
 *
 * <p>
 * Represents a variable (local variable, parameter, or field) in the debuggee,
 * captured at a specific point in the execution. Variables can be expanded to
 * show their sub-properties through the variableReference.
 *
 * <p>
 * The variableReference field enables lazy loading of complex object
 * properties:
 * <ul>
 * <li>0 = primitive value, not expandable</li>
 * <li>&gt;0 = has properties/children, reference can be used to fetch them</li>
 * </ul>
 */
public record VariableInfo(String name, String type, String value, int variableReference, // 0 if not expandable, >0 if
                                                                                          // has children
    String scope // "local", "this", "static"
) {
  /**
   * Validates that variable information is coherent.
   *
   * @return true if the variable is valid
   */
  public boolean isValid() {
    return name != null && !name.isEmpty() && type != null && !type.isEmpty() && scope != null && !scope.isEmpty();
  }

  /**
   * Checks if this variable can be expanded to show children.
   *
   * @return true if variableReference is greater than 0
   */
  public boolean isExpandable() {
    return variableReference > 0;
  }

  /**
   * Checks if this variable is a primitive type.
   *
   * @return true if the variable cannot be expanded
   */
  public boolean isPrimitive() {
    return !isExpandable();
  }

  /**
   * Checks if this variable is a method parameter.
   *
   * @return true if scope is "parameter" or starts with "param"
   */
  public boolean isParameter() {
    return "parameter".equals(scope) || scope.startsWith("param");
  }

  /**
   * Checks if this variable is the 'this' reference.
   *
   * @return true if it's the implicit 'this' variable
   */
  public boolean isThis() {
    return "this".equals(name) && "this".equals(scope);
  }

  /**
   * Gets a safe string representation of the value.
   *
   * <p>
   * Limits the value string to prevent excessively long outputs.
   *
   * @param maxLength maximum length for the value string
   * @return the value string, truncated if necessary
   */
  public String getSafeValue(int maxLength) {
    if (value == null) {
      return "null";
    }
    if (value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength - 3) + "...";
  }

  /**
   * Gets the safe value with a default max length of 100 characters.
   *
   * @return the value string, truncated if necessary
   */
  public String getSafeValue() {
    return getSafeValue(100);
  }

  /**
   * Gets a concise display string for this variable.
   *
   * @return formatted variable display
   */
  public String toShortString() {
    String display = String.format("%s: %s", name, type);
    if (isExpandable()) {
      display += " {...}";
    } else {
      display += String.format(" = %s", getSafeValue(50));
    }
    return display;
  }

  /**
   * Gets a detailed description of this variable.
   *
   * @return formatted variable details
   */
  @Override
  public String toString() {
    return String.format("Variable[%s, scope=%s]: %s = %s%s", name, scope, type, getSafeValue(),
        isExpandable() ? " (expandable)" : "");
  }
}
