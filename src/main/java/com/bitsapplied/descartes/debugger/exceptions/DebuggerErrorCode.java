package com.bitsapplied.descartes.debugger.exceptions;

/**
 * Standard error codes for debugger operations.
 *
 * <p>
 * Error codes are organized by category:
 * <ul>
 * <li>1000-1099: Session errors</li>
 * <li>1100-1199: Breakpoint errors</li>
 * <li>1200-1299: Thread errors</li>
 * <li>1300-1399: Variable errors</li>
 * <li>1400-1499: Expression evaluation errors</li>
 * <li>1500-1599: Hot reload errors</li>
 * <li>9999: Unknown/generic errors</li>
 * </ul>
 */
public enum DebuggerErrorCode {

  // Session errors (1000-1099)
  SESSION_NOT_ACTIVE(1000, "No active debug session"), SESSION_ALREADY_ACTIVE(1001, "Debug session already active"),
  SESSION_START_FAILED(1002, "Failed to start debug session"),
  JDWP_CONNECTION_FAILED(1003, "Failed to connect to JDWP"),
  SESSION_DISCONNECT_FAILED(1004, "Failed to disconnect debug session"),
  SESSION_INVALID_STATE(1005, "Invalid session state for this operation"),

  // Breakpoint errors (1100-1199)
  BREAKPOINT_SET_FAILED(1100, "Failed to set breakpoint"),
  BREAKPOINT_REMOVE_FAILED(1101, "Failed to remove breakpoint"), BREAKPOINT_NOT_FOUND(1102, "Breakpoint not found"),
  BREAKPOINT_INVALID_LOCATION(1103, "Invalid breakpoint location"),
  BREAKPOINT_CLASS_NOT_FOUND(1104, "Class not found for breakpoint"),
  BREAKPOINT_LINE_NOT_EXECUTABLE(1105, "Line is not executable"),
  BREAKPOINT_ALREADY_EXISTS(1106, "Breakpoint already exists at this location"),

  // Thread errors (1200-1299)
  THREAD_NOT_FOUND(1200, "Thread not found"), THREAD_NOT_SUSPENDED(1201, "Thread is not suspended"),
  THREAD_ALREADY_SUSPENDED(1202, "Thread is already suspended"), THREAD_RESUME_FAILED(1203, "Failed to resume thread"),
  THREAD_SUSPEND_FAILED(1204, "Failed to suspend thread"),

  // Variable errors (1300-1399)
  VARIABLE_NOT_FOUND(1300, "Variable not found"), VARIABLE_INVALID_REFERENCE(1301, "Invalid variable reference"),
  VARIABLE_SET_FAILED(1302, "Failed to set variable value"),
  VARIABLE_FETCH_FAILED(1303, "Failed to fetch variable value"),

  // Expression evaluation errors (1400-1499)
  EVALUATION_FAILED(1400, "Expression evaluation failed"), EVALUATION_TIMEOUT(1401, "Expression evaluation timeout"),
  EVALUATION_COMPILATION_FAILED(1402, "Failed to compile expression"),
  EVALUATION_EXECUTION_FAILED(1403, "Failed to execute expression"),
  EVALUATION_TYPE_MISMATCH(1404, "Type mismatch in expression"),

  // Hot reload errors (1500-1599)
  HOT_RELOAD_FAILED(1500, "Hot reload failed"), HOT_RELOAD_NOT_SUPPORTED(1501, "Hot reload not supported"),
  HOT_RELOAD_CLASS_INCOMPATIBLE(1502, "Class changes incompatible with hot reload"),

  // Generic/Unknown (9999)
  UNKNOWN_ERROR(9999, "Unknown error occurred"), INVALID_OPERATION(9998, "Invalid operation"),
  INVALID_PARAMETERS(9997, "Invalid parameters"), INVALID_FRAME(9996, "Invalid stack frame"),
  INTERNAL_ERROR(9995, "Internal error"), OPERATION_TIMEOUT(9994, "Debugger operation timed out");

  private final int code;
  private final String message;

  DebuggerErrorCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  /**
   * Gets the numeric error code.
   *
   * @return the error code
   */
  public int getCode() {
    return code;
  }

  /**
   * Gets the human-readable error message.
   *
   * @return the error message
   */
  public String getMessage() {
    return message;
  }

  /**
   * Finds an error code by its numeric code value.
   *
   * @param code the numeric code
   * @return the corresponding DebuggerErrorCode, or UNKNOWN_ERROR if not found
   */
  public static DebuggerErrorCode fromCode(int code) {
    for (DebuggerErrorCode errorCode : values()) {
      if (errorCode.code == code) {
        return errorCode;
      }
    }
    return UNKNOWN_ERROR;
  }
}
