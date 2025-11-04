package com.bitsapplied.descartes.debugger.variables;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.models.VariableInfo;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

/**
 * Extracts and formats variables from JDI stack frames and object references.
 *
 * <p>
 * Capabilities:
 * <ul>
 * <li>Extract local variables from stack frames</li>
 * <li>Extract fields from object references</li>
 * <li>Format primitive values and object references</li>
 * <li>Handle null values and exceptions gracefully</li>
 * <li>Assign variable references for lazy loading</li>
 * </ul>
 *
 * <p>
 * Thread Safety: All operations must be called on the debugger executor thread.
 */
public class VariableExtractor {
  private static final Logger logger = LoggerFactory.getLogger(VariableExtractor.class);

  /**
   * Maximum depth for object graph expansion to prevent stack overflow on
   * circular references. For example: Node -> Node.next -> Node.next.next -> ...
   * (circular)
   */
  private static final int MAX_EXPANSION_DEPTH = 10;

  /**
   * Maximum string length before truncation in variable display. Configurable to
   * balance readability vs. completeness.
   */
  private static final int MAX_STRING_DISPLAY_LENGTH = 200;

  /**
   * Number of characters to show when truncating strings. Shows first N
   * characters followed by "..."
   */
  private static final int STRING_TRUNCATE_AT = MAX_STRING_DISPLAY_LENGTH - 3; // Reserve 3 chars for "..."

  private final VariableReferenceManager referenceManager;

  /**
   * Creates a variable extractor with a reference manager.
   *
   * @param referenceManager the reference manager for lazy loading
   */
  public VariableExtractor(VariableReferenceManager referenceManager) {
    this.referenceManager = referenceManager;
  }

  /**
   * Extracts all variables visible in a stack frame.
   *
   * @param frame the stack frame
   * @return list of variable information
   */
  public List<VariableInfo> extractVariables(StackFrame frame) {
    List<VariableInfo> variables = new ArrayList<>();

    try {
      // Extract 'this' reference (if available)
      ObjectReference thisObject = frame.thisObject();
      if (thisObject != null) {
        VariableInfo thisVar = createVariableInfo("this", thisObject.type().name(), thisObject, "this");
        variables.add(thisVar);
      }

      // Extract local variables and parameters
      Map<LocalVariable, Value> visibleVariables = frame.getValues(frame.visibleVariables());

      for (Map.Entry<LocalVariable, Value> entry : visibleVariables.entrySet()) {
        LocalVariable localVar = entry.getKey();
        Value value = entry.getValue();

        String scope = localVar.isArgument() ? "parameter" : "local";

        VariableInfo varInfo = createVariableInfo(localVar.name(), localVar.typeName(), value, scope);

        variables.add(varInfo);
      }

    } catch (AbsentInformationException e) {
      logger.debug("No variable information available for frame");
    } catch (Exception e) {
      logger.warn("Error extracting variables from frame: {}", e.getMessage());
    }

    return variables;
  }

  /**
   * Extracts child variables from an object reference using a variable reference
   * ID.
   *
   * @param variableReference the variable reference ID
   * @return list of child variable information
   */
  public List<VariableInfo> extractChildVariables(int variableReference) {
    return extractChildVariables(variableReference, 0);
  }

  /**
   * Extracts child variables with depth tracking to prevent infinite recursion.
   *
   * @param variableReference the variable reference ID
   * @param depth             current expansion depth
   * @return list of child variable information
   */
  private List<VariableInfo> extractChildVariables(int variableReference, int depth) {
    if (depth >= MAX_EXPANSION_DEPTH) {
      logger.debug("Maximum expansion depth {} reached for variable reference {}", MAX_EXPANSION_DEPTH,
          variableReference);
      return List.of();
    }

    ObjectReference object = referenceManager.getObjectReference(variableReference);

    if (object == null) {
      logger.warn("No object found for variable reference: {}", variableReference);
      return List.of();
    }

    return extractFieldsFromObject(object, depth);
  }

  /**
   * Extracts fields from an object reference.
   *
   * @param object the object reference
   * @return list of field variable information
   */
  public List<VariableInfo> extractFieldsFromObject(ObjectReference object) {
    return extractFieldsFromObject(object, 0);
  }

  /**
   * Extracts fields from an object reference with depth tracking.
   *
   * @param object the object reference
   * @param depth  current expansion depth
   * @return list of field variable information
   */
  private List<VariableInfo> extractFieldsFromObject(ObjectReference object, int depth) {
    List<VariableInfo> fields = new ArrayList<>();

    try {
      ReferenceType refType = object.referenceType();
      List<Field> allFields = refType.allFields();

      Map<Field, Value> fieldValues = object.getValues(allFields);

      for (Map.Entry<Field, Value> entry : fieldValues.entrySet()) {
        Field field = entry.getKey();
        Value value = entry.getValue();

        String scope = field.isStatic() ? "static" : "field";

        VariableInfo fieldInfo = createVariableInfo(field.name(), field.typeName(), value, scope, depth);

        fields.add(fieldInfo);
      }

    } catch (Exception e) {
      logger.warn("Error extracting fields from object: {}", e.getMessage());
    }

    return fields;
  }

  /**
   * Extracts static fields from a class type.
   *
   * @param classType the class type
   * @return list of static field variable information
   */
  public List<VariableInfo> extractStaticFields(ReferenceType classType) {
    List<VariableInfo> staticFields = new ArrayList<>();

    try {
      List<Field> allFields = classType.allFields();

      for (Field field : allFields) {
        if (field.isStatic()) {
          Value value = classType.getValue(field);

          VariableInfo fieldInfo = createVariableInfo(field.name(), field.typeName(), value, "static");

          staticFields.add(fieldInfo);
        }
      }

    } catch (Exception e) {
      logger.warn("Error extracting static fields: {}", e.getMessage());
    }

    return staticFields;
  }

  // ========== Internal Methods ==========

  /**
   * Creates a VariableInfo record from JDI Value.
   */
  private VariableInfo createVariableInfo(String name, String typeName, Value value, String scope) {
    return createVariableInfo(name, typeName, value, scope, 0);
  }

  /**
   * Creates a VariableInfo record from JDI Value with depth tracking.
   */
  private VariableInfo createVariableInfo(String name, String typeName, Value value, String scope, int depth) {
    String valueStr = formatValue(value);
    int variableReference = 0;

    // Assign variable reference for expandable objects (only if we haven't hit max
    // depth)
    if (value instanceof ObjectReference objRef && depth < MAX_EXPANSION_DEPTH) {
      if (isExpandable(objRef)) {
        variableReference = referenceManager.registerObjectReference(objRef);
      }
    }

    return new VariableInfo(name, typeName, valueStr, variableReference, scope);
  }

  /**
   * Formats a JDI Value to a string representation.
   */
  private String formatValue(Value value) {
    if (value == null) {
      return "null";
    }

    try {
      // Primitive values
      if (value instanceof PrimitiveValue primitiveValue) {
        return primitiveValue.toString();
      }

      // String values
      if (value instanceof StringReference stringRef) {
        try {
          String str = stringRef.value();
          // Truncate long strings for display
          if (str.length() > MAX_STRING_DISPLAY_LENGTH) {
            return "\"" + str.substring(0, STRING_TRUNCATE_AT) + "...\"";
          }
          return "\"" + str + "\"";
        } catch (Exception stringEx) {
          logger.trace("Error reading string value: {}", stringEx.getMessage());
          return "<string: error reading value>";
        }
      }

      // Array values
      if (value instanceof ArrayReference arrayRef) {
        try {
          int length = arrayRef.length();
          return String.format("%s[%d]", arrayRef.type().name(), length);
        } catch (Exception arrayEx) {
          logger.trace("Error reading array length: {}", arrayEx.getMessage());
          return arrayRef.type().name() + "[?]";
        }
      }

      // Object references
      if (value instanceof ObjectReference objRef) {
        // Try to get a meaningful string representation
        String typeName = objRef.type().name();

        // For common types, try to extract a preview
        if (typeName.equals("java.util.ArrayList") || typeName.equals("java.util.LinkedList")) {
          return formatCollectionPreview(objRef);
        } else if (typeName.equals("java.util.HashMap") || typeName.equals("java.util.TreeMap")) {
          return formatMapPreview(objRef);
        } else {
          return typeName + " {...}";
        }
      }

      return value.toString();

    } catch (Exception e) {
      logger.trace("Error formatting value: {}", e.getMessage());
      return "<error: " + e.getMessage() + ">";
    }
  }

  /**
   * Checks if an object reference is expandable (has fields to show).
   */
  private boolean isExpandable(ObjectReference objRef) {
    try {
      // Arrays are expandable if they have elements
      if (objRef instanceof ArrayReference arrayRef) {
        return arrayRef.length() > 0;
      }

      // Objects are expandable if they have non-static fields
      ReferenceType refType = objRef.referenceType();
      return refType.allFields().stream().anyMatch(f -> !f.isStatic());

    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Formats a collection preview showing size.
   */
  private String formatCollectionPreview(ObjectReference objRef) {
    try {
      Field sizeField = objRef.referenceType().fieldByName("size");
      if (sizeField != null) {
        Value sizeValue = objRef.getValue(sizeField);
        if (sizeValue instanceof IntegerValue intVal) {
          return String.format("%s (size=%d)", objRef.type().name(), intVal.value());
        }
      }
    } catch (Exception e) {
      // Fall through
    }
    return objRef.type().name() + " {...}";
  }

  /**
   * Formats a map preview showing size.
   */
  private String formatMapPreview(ObjectReference objRef) {
    try {
      Field sizeField = objRef.referenceType().fieldByName("size");
      if (sizeField != null) {
        Value sizeValue = objRef.getValue(sizeField);
        if (sizeValue instanceof IntegerValue intVal) {
          return String.format("%s (size=%d)", objRef.type().name(), intVal.value());
        }
      }
    } catch (Exception e) {
      // Fall through
    }
    return objRef.type().name() + " {...}";
  }
}
