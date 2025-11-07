package com.bitsapplied.descartes.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for ParameterUtils.
 */
class ParameterUtilsTest {

  // ==================== getString Tests ====================

  @Test
  void testGetString_WithValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "value");
    assertEquals("value", ParameterUtils.getString(params, "key", "default"));
  }

  @Test
  void testGetString_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    assertEquals("default", ParameterUtils.getString(params, "missing", "default"));
  }

  @Test
  void testGetString_WithNull() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", null);
    assertEquals("default", ParameterUtils.getString(params, "key", "default"));
  }

  @Test
  void testGetString_WithNullParams() {
    assertEquals("default", ParameterUtils.getString(null, "key", "default"));
  }

  @Test
  void testGetString_ConvertsToString() {
    Map<String, Object> params = new HashMap<>();
    params.put("number", 42);
    assertEquals("42", ParameterUtils.getString(params, "number", "default"));
  }

  // ==================== getInt Tests ====================

  @Test
  void testGetInt_WithIntegerValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertEquals(42, ParameterUtils.getInt(params, "key", 0));
  }

  @Test
  void testGetInt_WithStringValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "42");
    assertEquals(42, ParameterUtils.getInt(params, "key", 0));
  }

  @Test
  void testGetInt_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    assertEquals(99, ParameterUtils.getInt(params, "missing", 99));
  }

  @Test
  void testGetInt_WithNull() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", null);
    assertEquals(99, ParameterUtils.getInt(params, "key", 99));
  }

  @Test
  void testGetInt_WithInvalidString() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "not-a-number");
    assertEquals(99, ParameterUtils.getInt(params, "key", 99));
  }

  @Test
  void testGetInt_WithDoubleValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42.7);
    assertEquals(42, ParameterUtils.getInt(params, "key", 0));
  }

  @Test
  void testGetInt_WithInvalidType() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", new Object());
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getInt(params, "key", 0));
  }

  // ==================== getLong Tests ====================

  @Test
  void testGetLong_WithLongValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 123456789L);
    assertEquals(123456789L, ParameterUtils.getLong(params, "key", 0L));
  }

  @Test
  void testGetLong_WithStringValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "123456789");
    assertEquals(123456789L, ParameterUtils.getLong(params, "key", 0L));
  }

  @Test
  void testGetLong_WithIntegerValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertEquals(42L, ParameterUtils.getLong(params, "key", 0L));
  }

  // ==================== getBoolean Tests ====================

  @Test
  void testGetBoolean_WithBooleanValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", true);
    assertTrue(ParameterUtils.getBoolean(params, "key", false));
  }

  @Test
  void testGetBoolean_WithStringValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "true");
    assertTrue(ParameterUtils.getBoolean(params, "key", false));
  }

  @Test
  void testGetBoolean_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    assertTrue(ParameterUtils.getBoolean(params, "missing", true));
  }

  @Test
  void testGetBoolean_WithInvalidType() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getBoolean(params, "key", false));
  }

  // ==================== getDouble Tests ====================

  @Test
  void testGetDouble_WithDoubleValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 3.14);
    assertEquals(3.14, ParameterUtils.getDouble(params, "key", 0.0));
  }

  @Test
  void testGetDouble_WithStringValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "3.14");
    assertEquals(3.14, ParameterUtils.getDouble(params, "key", 0.0), 0.001);
  }

  @Test
  void testGetDouble_WithIntegerValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertEquals(42.0, ParameterUtils.getDouble(params, "key", 0.0));
  }

  // ==================== Required Parameter Tests ====================

  @Test
  void testGetRequiredString_WithValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "value");
    assertEquals("value", ParameterUtils.getRequiredString(params, "key"));
  }

  @Test
  void testGetRequiredString_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getRequiredString(params, "missing"));
  }

  @Test
  void testGetRequiredString_WithNull() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", null);
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getRequiredString(params, "key"));
  }

  @Test
  void testGetRequiredInt_WithValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertEquals(42, ParameterUtils.getRequiredInt(params, "key"));
  }

  @Test
  void testGetRequiredInt_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getRequiredInt(params, "missing"));
  }

  @Test
  void testGetRequiredInt_WithNull() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", null);
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getRequiredInt(params, "key"));
  }

  @Test
  void testGetRequiredLong_WithValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 123456789L);
    assertEquals(123456789L, ParameterUtils.getRequiredLong(params, "key"));
  }

  // ==================== Validated Parameter Tests ====================

  @Test
  void testGetInt_WithRangeValidation_Valid() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 50);
    assertEquals(50, ParameterUtils.getInt(params, "key", 0, 1, 100));
  }

  @Test
  void testGetInt_WithRangeValidation_TooLow() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 0);
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getInt(params, "key", 50, 1, 100));
  }

  @Test
  void testGetInt_WithRangeValidation_TooHigh() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 150);
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getInt(params, "key", 50, 1, 100));
  }

  @Test
  void testGetDouble_WithRangeValidation_Valid() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 5.5);
    assertEquals(5.5, ParameterUtils.getDouble(params, "key", 0.0, 0.0, 10.0));
  }

  @Test
  void testGetDouble_WithRangeValidation_TooLow() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", -1.0);
    assertThrows(IllegalArgumentException.class, () -> ParameterUtils.getDouble(params, "key", 5.0, 0.0, 10.0));
  }

  // ==================== Collection Tests ====================

  @Test
  void testGetStringList_WithList() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", Arrays.asList("a", "b", "c"));
    List<String> result = ParameterUtils.getStringList(params, "key");
    assertEquals(3, result.size());
    assertEquals("a", result.get(0));
    assertEquals("b", result.get(1));
    assertEquals("c", result.get(2));
  }

  @Test
  void testGetStringList_WithArray() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", new String[] { "a", "b", "c" });
    List<String> result = ParameterUtils.getStringList(params, "key");
    assertEquals(3, result.size());
    assertEquals("a", result.get(0));
  }

  @Test
  void testGetStringList_WithSingleValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "single");
    List<String> result = ParameterUtils.getStringList(params, "key");
    assertEquals(1, result.size());
    assertEquals("single", result.get(0));
  }

  @Test
  void testGetStringList_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    List<String> result = ParameterUtils.getStringList(params, "missing");
    assertTrue(result.isEmpty());
  }

  @Test
  void testGetStringList_WithNull() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", null);
    List<String> result = ParameterUtils.getStringList(params, "key");
    assertTrue(result.isEmpty());
  }

  @Test
  void testGetStringList_ConvertsToString() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", Arrays.asList(1, 2, 3));
    List<String> result = ParameterUtils.getStringList(params, "key");
    assertEquals(3, result.size());
    assertEquals("1", result.get(0));
    assertEquals("2", result.get(1));
    assertEquals("3", result.get(2));
  }

  @Test
  void testGetStringArray_WithList() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", Arrays.asList("a", "b", "c"));
    String[] result = ParameterUtils.getStringArray(params, "key", null);
    assertNotNull(result);
    assertEquals(3, result.length);
    assertEquals("a", result[0]);
  }

  @Test
  void testGetStringArray_WithMissing() {
    Map<String, Object> params = new HashMap<>();
    String[] defaultValue = new String[] { "default" };
    String[] result = ParameterUtils.getStringArray(params, "missing", defaultValue);
    assertSame(defaultValue, result);
  }
}
