package com.bitsapplied.descartes.mcp.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JsonRpcValidator {
  private final ObjectMapper objectMapper;
  private final int maxMessageSizeBytes;

  JsonRpcValidator(ObjectMapper objectMapper, int maxMessageSizeBytes) {
    this.objectMapper = objectMapper;
    this.maxMessageSizeBytes = maxMessageSizeBytes;
  }

  ValidationResult validate(String message) {
    if (message == null || message.isBlank()) {
      return ValidationResult.invalid("Empty message");
    }

    if (message.length() > maxMessageSizeBytes) {
      return ValidationResult.invalid("Message exceeds maximum size limit");
    }

    try {
      JsonNode parsed = objectMapper.readTree(message);
      JsonNode versionNode = parsed.path("jsonrpc");
      if (!versionNode.isTextual() || !"2.0".equals(versionNode.asText())) {
        return ValidationResult.invalid("Invalid JSON-RPC version");
      }

      if (parsed.has("method")) {
        JsonNode methodNode = parsed.get("method");
        if (!methodNode.isTextual()) {
          return ValidationResult.invalid("Invalid method field");
        }
      }

      if (parsed.has("result") && parsed.has("error")) {
        return ValidationResult.invalid("Response cannot have both result and error");
      }

      return ValidationResult.valid(parsed);
    } catch (JsonProcessingException e) {
      return ValidationResult.invalid("Invalid JSON");
    }
  }

  ObjectNode newNotification(String method) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("jsonrpc", "2.0");
    node.put("method", method);
    node.set("params", objectMapper.createObjectNode());
    return node;
  }

  static JsonNode extractId(JsonNode parsed) {
    if (parsed == null || !parsed.has("id")) {
      return null;
    }
    JsonNode idNode = parsed.get("id");
    return idNode.isNull() ? null : idNode;
  }

  static boolean isInitialize(JsonNode parsed) {
    if (parsed == null) {
      return false;
    }
    JsonNode method = parsed.get("method");
    return method != null && method.isTextual() && "initialize".equals(method.asText());
  }

  record ValidationResult(boolean valid, JsonNode parsed, String error) {
    static ValidationResult valid(JsonNode parsed) {
      return new ValidationResult(true, parsed, null);
    }

    static ValidationResult invalid(String error) {
      return new ValidationResult(false, null, error);
    }
  }
}
