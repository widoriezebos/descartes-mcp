package com.bitsapplied.descartes.util;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jdk.jshell.SnippetEvent;

/**
 * Evaluation result from JShell. Serializes via JsonUtils.toJSON(this).
 */
public class EvalResult {

  /** Minimal event projection. */
  public static final class SnippetResult {
    private final String source;
    private final String value;
    private final String status;
    private final String exceptionType;
    private final String exceptionMessage;

    public SnippetResult(String source, String value, String status, String exceptionType, String exceptionMessage) {
      this.source = source;
      this.value = value;
      this.status = status;
      this.exceptionType = exceptionType;
      this.exceptionMessage = exceptionMessage;
    }

    // Plain API
    public String source() {
      return source;
    }

    public String value() {
      return value;
    }

    public String status() {
      return status;
    }

    public String exceptionType() {
      return exceptionType;
    }

    public String exceptionMessage() {
      return exceptionMessage;
    }

    // Bean getters for Jackson
    @JsonProperty("source")
    public String getSource() {
      return source;
    }

    @JsonProperty("value")
    public String getValue() {
      return value;
    }

    @JsonProperty("status")
    public String getStatus() {
      return status;
    }

    @JsonProperty("exceptionType")
    public String getExceptionType() {
      return exceptionType;
    }

    @JsonProperty("exceptionMessage")
    public String getExceptionMessage() {
      return exceptionMessage;
    }
  }

  private final String out;
  private final String err;
  private final List<SnippetResult> events;
  private final Instant startedAt;
  private final Instant finishedAt;
  private final String sessionId;

  public EvalResult(String out, String err, List<SnippetEvent> events, Instant startedAt, Instant finishedAt) {
    this(out, err, events, startedAt, finishedAt, null);
  }

  public EvalResult(String out, String err, List<SnippetEvent> events, Instant startedAt, Instant finishedAt,
      String sessionId) {
    this.out = out;
    this.err = err;
    this.events = Collections
        .unmodifiableList(events.stream().map(EvalResult::toSnippetResult).collect(Collectors.toList()));
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.sessionId = sessionId;
  }

  // Constructor that directly accepts SnippetResults for creating modified
  // results
  private EvalResult(String out, String err, List<SnippetResult> events, Instant startedAt, Instant finishedAt,
      String sessionId, boolean direct) {
    this.out = out;
    this.err = err;
    this.events = Collections.unmodifiableList(events);
    this.startedAt = startedAt;
    this.finishedAt = finishedAt;
    this.sessionId = sessionId;
  }

  public EvalResult withSessionId(String sessionId) {
    return new EvalResult(this.out, this.err, this.events, this.startedAt, this.finishedAt, sessionId, true);
  }

  // Existing API
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @JsonProperty("out")
  public String out() {
    return out;
  }

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  @JsonProperty("err")
  public String err() {
    return err != null ? err : "";
  }

  public List<SnippetResult> events() {
    return events;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }

  public String sessionId() {
    return sessionId;
  }

  // Bean getters for Jackson
  @JsonProperty("out")
  public String getOut() {
    return out;
  }

  @JsonProperty("err")
  public String getErr() {
    return err;
  }

  @JsonProperty("events")
  public List<SnippetResult> getEvents() {
    return events;
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonProperty("sessionId")
  public String getSessionId() {
    return sessionId;
  }

  // Hide raw Instants from Jackson to avoid jsr310 module requirement
  @JsonIgnore
  public Instant getStartedAt() {
    return startedAt;
  }

  @JsonIgnore
  public Instant getFinishedAt() {
    return finishedAt;
  }

  // Expose ISO-8601 strings instead
  @JsonProperty("startedAt")
  public String getStartedAtIso() {
    return startedAt != null ? startedAt.toString() : null;
  }

  @JsonProperty("finishedAt")
  public String getFinishedAtIso() {
    return finishedAt != null ? finishedAt.toString() : null;
  }

  private static SnippetResult toSnippetResult(SnippetEvent e) {
    String src = (e.snippet() != null) ? e.snippet().source() : null;
    String val = (e.value() != null && !e.value().isBlank()) ? e.value() : null;
    String status = (e.status() != null) ? e.status().toString() : null;
    String exType = (e.exception() != null) ? e.exception().getClass().getSimpleName() : null;
    String exMsg = (e.exception() != null) ? e.exception().getMessage() : null;
    return new SnippetResult(src, val, status, exType, exMsg);
  }

  @Override
  public String toString() {
    return JsonUtils.toJSON(this);
  }
}
