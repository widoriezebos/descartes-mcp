package com.bitsapplied.descartes.hotreload.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of validating whether classes can be safely redefined.
 * 
 * @author Descartes MCP
 */
public class ValidationResult {

  private final boolean valid;
  private final List<String> errors;

  /**
   * Create a validation result with errors.
   * 
   * @param errors List of validation errors
   */
  public ValidationResult(List<String> errors) {
    this.errors = errors != null ? new ArrayList<>(errors) : new ArrayList<>();
    this.valid = this.errors.isEmpty();
  }

  /**
   * Create a successful validation result.
   * 
   * @return Successful validation result
   */
  public static ValidationResult success() {
    return new ValidationResult(null);
  }

  /**
   * Create a failed validation result with a single error.
   * 
   * @param error Error message
   * @return Failed validation result
   */
  public static ValidationResult failed(String error) {
    List<String> errors = new ArrayList<>();
    errors.add(error);
    return new ValidationResult(errors);
  }

  /**
   * Check if validation passed.
   * 
   * @return true if validation passed
   */
  public boolean isValid() {
    return valid;
  }

  /**
   * Get validation errors.
   * 
   * @return List of errors (empty if valid)
   */
  public List<String> getErrors() {
    return new ArrayList<>(errors);
  }

  /**
   * Get a combined error message.
   * 
   * @return Combined error message or null if valid
   */
  public String getErrorMessage() {
    if (valid) {
      return null;
    }
    return String.join("; ", errors);
  }

  @Override
  public String toString() {
    if (valid) {
      return "ValidationResult[VALID]";
    } else {
      return "ValidationResult[INVALID: " + errors.size() + " errors]";
    }
  }
}