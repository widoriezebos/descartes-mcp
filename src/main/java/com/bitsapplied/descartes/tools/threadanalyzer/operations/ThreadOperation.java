package com.bitsapplied.descartes.tools.threadanalyzer.operations;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy interface for thread analyzer operations.
 * Each operation implements its own logic for analyzing threads.
 */
public interface ThreadOperation {

  /**
   * Execute the operation asynchronously.
   *
   * @param args the operation arguments
   * @return a CompletableFuture with the operation result
   */
  CompletableFuture<Map<String, Object>> executeAsync(Map<String, Object> args);

  /**
   * Get the operation name.
   *
   * @return the operation name
   */
  String getOperationName();
}
