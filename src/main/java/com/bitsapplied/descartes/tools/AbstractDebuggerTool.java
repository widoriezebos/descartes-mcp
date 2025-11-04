package com.bitsapplied.descartes.tools;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.bitsapplied.descartes.debugger.DebuggerService;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

/**
 * Base class for debugger-related MCP tools providing common async execution
 * and error handling.
 */
public abstract class AbstractDebuggerTool implements MCPTool {

  protected final DebuggerService debuggerService;

  protected AbstractDebuggerTool(DebuggerService debuggerService) {
    this.debuggerService = Objects.requireNonNull(debuggerService, "debuggerService");
  }

  @Override
  public CompletableFuture<ToolResponse> executeAsync(Map<String, Object> arguments) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return executeInternal(arguments);
      } catch (DebuggerException e) {
        return ToolResponse.error(e.getErrorCode().getCode(), e.getMessage());
      } catch (ToolExecutionException e) {
        return ToolResponse.error(e.getErrorCode(), e.getMessage(),
            e.getCause() != null ? e.getCause().getMessage() : "");
      } catch (Exception e) {
        return ToolResponse.error(DebuggerErrorCode.INTERNAL_ERROR.getCode(),
            "Debugger tool failed: " + e.getMessage());
      }
    }, debuggerService.getDebuggerExecutor());
  }

  /**
   * Tool-specific business logic. Implementations should return a pre-built
   * {@link ToolResponse} and may throw exceptions that will be converted into
   * structured errors.
   */
  protected abstract ToolResponse executeInternal(Map<String, Object> arguments) throws Exception;
}
