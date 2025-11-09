package com.bitsapplied.descartes.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;

/**
 * Tests for DebuggerParameterUtils - verifies proper exception conversion.
 */
class DebuggerParameterUtilsTest {

  // ==================== getString Tests ====================

  @Test
  void testGetString_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "value");
    assertEquals("value", DebuggerParameterUtils.getString(params, "key", "default"));
  }

  @Test
  void testGetString_WithDefault() {
    Map<String, Object> params = new HashMap<>();
    assertEquals("default", DebuggerParameterUtils.getString(params, "missing", "default"));
  }

  // ==================== getInt Tests ====================

  @Test
  void testGetInt_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertEquals(42, DebuggerParameterUtils.getInt(params, "key", 0));
  }

  @Test
  void testGetInt_WithStringValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "42");
    assertEquals(42, DebuggerParameterUtils.getInt(params, "key", 0));
  }

  @Test
  void testGetInt_WithInvalidType_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", new Object());
    DebuggerException ex = assertThrows(DebuggerException.class, () -> DebuggerParameterUtils.getInt(params, "key", 0));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
  }

  // ==================== getLong Tests ====================

  @Test
  void testGetLong_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 123456789L);
    assertEquals(123456789L, DebuggerParameterUtils.getLong(params, "key", 0L));
  }

  // ==================== getBoolean Tests ====================

  @Test
  void testGetBoolean_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", true);
    assertTrue(DebuggerParameterUtils.getBoolean(params, "key", false));
  }

  @Test
  void testGetBoolean_WithStringValue() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "true");
    assertTrue(DebuggerParameterUtils.getBoolean(params, "key", false));
  }

  @Test
  void testGetBoolean_WithInvalidType_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    DebuggerException ex = assertThrows(DebuggerException.class,
        () -> DebuggerParameterUtils.getBoolean(params, "key", false));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
  }

  // ==================== getDouble Tests ====================

  @Test
  void testGetDouble_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 3.14);
    assertEquals(3.14, DebuggerParameterUtils.getDouble(params, "key", 0.0));
  }

  // ==================== Required Parameter Tests ====================

  @Test
  void testGetRequiredString_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", "value");
    assertEquals("value", DebuggerParameterUtils.getRequiredString(params, "key"));
  }

  @Test
  void testGetRequiredString_WithMissing_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    DebuggerException ex = assertThrows(DebuggerException.class,
        () -> DebuggerParameterUtils.getRequiredString(params, "missing"));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("missing"));
  }

  @Test
  void testGetRequiredInt_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 42);
    assertEquals(42, DebuggerParameterUtils.getRequiredInt(params, "key"));
  }

  @Test
  void testGetRequiredInt_WithMissing_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    DebuggerException ex = assertThrows(DebuggerException.class,
        () -> DebuggerParameterUtils.getRequiredInt(params, "missing"));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
  }

  @Test
  void testGetRequiredLong_Success() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 123456789L);
    assertEquals(123456789L, DebuggerParameterUtils.getRequiredLong(params, "key"));
  }

  @Test
  void testGetRequiredLong_WithMissing_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    DebuggerException ex = assertThrows(DebuggerException.class,
        () -> DebuggerParameterUtils.getRequiredLong(params, "missing"));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
  }

  // ==================== Validated Parameter Tests ====================

  @Test
  void testGetInt_WithRangeValidation_Valid() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 50);
    assertEquals(50, DebuggerParameterUtils.getInt(params, "key", 0, 1, 100));
  }

  @Test
  void testGetInt_WithRangeValidation_Invalid_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 150);
    DebuggerException ex = assertThrows(DebuggerException.class,
        () -> DebuggerParameterUtils.getInt(params, "key", 50, 1, 100));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
    assertTrue(ex.getMessage().contains("between"));
  }

  @Test
  void testGetDouble_WithRangeValidation_Valid() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 5.5);
    assertEquals(5.5, DebuggerParameterUtils.getDouble(params, "key", 0.0, 0.0, 10.0));
  }

  @Test
  void testGetDouble_WithRangeValidation_Invalid_ThrowsDebuggerException() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", 15.0);
    DebuggerException ex = assertThrows(DebuggerException.class,
        () -> DebuggerParameterUtils.getDouble(params, "key", 5.0, 0.0, 10.0));
    assertEquals(DebuggerErrorCode.INVALID_PARAMETERS, ex.getErrorCode());
  }

  // ==================== Collection Tests ====================

  @Test
  void testGetStringList_WithList() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", Arrays.asList("a", "b", "c"));
    List<String> result = DebuggerParameterUtils.getStringList(params, "key");
    assertEquals(3, result.size());
    assertEquals("a", result.get(0));
  }

  @Test
  void testGetStringList_WithMissing_ReturnsEmpty() {
    Map<String, Object> params = new HashMap<>();
    List<String> result = DebuggerParameterUtils.getStringList(params, "missing");
    assertTrue(result.isEmpty());
  }

  @Test
  void testGetStringArray_WithList() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", Arrays.asList("a", "b", "c"));
    String[] result = DebuggerParameterUtils.getStringArray(params, "key", null);
    assertNotNull(result);
    assertEquals(3, result.length);
    assertEquals("a", result[0]);
  }

  @Test
  void testGetStringArray_WithMissing_ReturnsDefault() {
    Map<String, Object> params = new HashMap<>();
    String[] defaultValue = new String[] { "default" };
    String[] result = DebuggerParameterUtils.getStringArray(params, "missing", defaultValue);
    assertSame(defaultValue, result);
  }

  // ==================== Exception Conversion Tests ====================

  @Test
  void testExceptionConversion_PreservesCause() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", new Object());
    try {
      DebuggerParameterUtils.getInt(params, "key", 0);
      fail("Should have thrown DebuggerException");
    } catch (DebuggerException ex) {
      assertNotNull(ex.getCause());
      assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }
  }

  @Test
  void testExceptionConversion_PreservesMessage() {
    Map<String, Object> params = new HashMap<>();
    params.put("key", new Object());
    try {
      DebuggerParameterUtils.getInt(params, "key", 0);
      fail("Should have thrown DebuggerException");
    } catch (DebuggerException ex) {
      assertTrue(ex.getMessage().contains("key"));
      assertTrue(ex.getMessage().contains("number"));
    }
  }

  // ==================== Null Safety Tests ====================

  @Test
  void testGetString_WithNullParams_ReturnsDefault() {
    assertEquals("default", DebuggerParameterUtils.getString(null, "key", "default"));
  }

  @Test
  void testGetInt_WithNullParams_ReturnsDefault() {
    assertEquals(42, DebuggerParameterUtils.getInt(null, "key", 42));
  }

  @Test
  void testGetStringList_WithNullParams_ReturnsEmpty() {
    List<String> result = DebuggerParameterUtils.getStringList(null, "key");
    assertTrue(result.isEmpty());
  }
}
