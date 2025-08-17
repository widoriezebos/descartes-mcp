package com.bitsapplied.descartes.util;

/**
 * Wrapper for EvalResult with session ID.
 */
public final class SessionEvalResult {

  private final EvalResult evalResult;
  private final String sessionId;

  public SessionEvalResult(EvalResult evalResult, String sessionId) {
    this.evalResult = evalResult;
    this.sessionId = sessionId;
  }

  public EvalResult getEvalResult() {
    return evalResult;
  }

  public String getSessionId() {
    return sessionId;
  }
}