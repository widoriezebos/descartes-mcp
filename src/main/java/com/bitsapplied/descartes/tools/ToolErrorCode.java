package com.bitsapplied.descartes.tools;

/**
 * Canonical error codes for MCP tools. Codes are grouped by category so the
 * MCP server can translate them into JSON-RPC error ranges automatically.
 *
 * <p>
 * Categories:
 * <ul>
 * <li>1000-1999: Validation and user input errors</li>
 * <li>2000-2999: Precondition failures or missing environment/context</li>
 * <li>3000-3999: Execution failures that occurred after validation</li>
 * </ul>
 */
public final class ToolErrorCode {

  private ToolErrorCode() {
    // Utility class
  }

  // 1xxx – validation
  public static final int VALIDATION_FAILED = 1000;
  public static final int MISSING_REQUIRED_PARAMETER = 1001;
  public static final int INVALID_PARAMETER_VALUE = 1002;
  public static final int UNSUPPORTED_OPERATION = 1003;
  public static final int RESPONSE_TOO_LARGE = 1004;

  // 2xxx – environment / preconditions
  public static final int PRECONDITION_FAILED = 2000;
  public static final int RESOURCE_UNAVAILABLE = 2001;
  public static final int CONTEXT_NOT_INITIALIZED = 2002;
  public static final int TIMEOUT = 2003;

  // 3xxx – execution/runtime failures
  public static final int EXECUTION_FAILED = 3000;
  public static final int INTERNAL_ERROR = 3999;
}
