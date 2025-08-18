package com.bitsapplied.descartes.hotreload.test;

/**
 * Test class used to test incompatible changes that should be rejected by hot
 * reload.
 */
public class IncompatibleChangeTestClass {

  // This field will be used to test field addition/removal
  private String existingField = "original";

  /**
   * Method that will have its signature changed (incompatible).
   */
  public String methodWithSignature(String param) {
    return "Original: " + param;
  }

  /**
   * Method that will remain unchanged.
   */
  public String unchangedMethod() {
    return "Unchanged";
  }

  /**
   * Get the existing field value.
   */
  public String getExistingField() {
    return existingField;
  }
}