package com.bitsapplied.descartes.util;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.bitsapplied.descartes.settings.Setting;

/**
 * Rich object inspection utilities for JShell sessions. Provides comprehensive
 * reflection-based inspection capabilities.
 *
 * <p>
 * Configuration can be overridden via system properties:
 * <ul>
 * <li>{@code jshell.inspector.collectionLimit} - max collection elements to
 * display (default: 10)</li>
 * <li>{@code jshell.inspector.defaultDepth} - default tree traversal depth
 * (default: 3)</li>
 * <li>{@code jshell.inspector.maxStringLength} - max string length before
 * truncation (default: 100)</li>
 * </ul>
 */
public final class JShellInspector {

  private static final int DEFAULT_COLLECTION_LIMIT = getConfigInt(Setting.JSHELL_INSPECTOR_COLLECTION_LIMIT);
  private static final int DEFAULT_DEPTH = getConfigInt(Setting.JSHELL_INSPECTOR_DEFAULT_DEPTH);
  private static final int MAX_STRING_LENGTH = getConfigInt(Setting.JSHELL_INSPECTOR_MAX_STRING_LENGTH);
  private static final Set<Class<?>> PRIMITIVE_WRAPPERS = Set.of(Boolean.class, Byte.class, Character.class,
      Short.class, Integer.class, Long.class, Float.class, Double.class, String.class);

  // Prevent instantiation
  private JShellInspector() {
  }

  /**
   * Gets configuration value from system property, falling back to Setting
   * default.
   */
  private static int getConfigInt(Setting setting) {
    String sysProp = System.getProperty(setting.key());
    if (sysProp != null) {
      try {
        return Integer.parseInt(sysProp);
      } catch (NumberFormatException e) {
        // Fall through to default
      }
    }
    return setting.defaultValue(Integer.class);
  }

  /**
   * Comprehensive object inspection with reflection. Shows type, fields, methods
   * summary, and current values.
   */
  public static void inspect(Object obj) {
    if (obj == null) {
      System.out.println("null");
      return;
    }

    Class<?> cls = obj.getClass();

    // Special handling for primitive types and strings
    if (isPrimitiveOrWrapper(cls) || cls == String.class) {
      System.out.println("=== Value Inspection ===");
      System.out.printf("Type: %s%n", cls.getName());
      System.out.printf("Value: %s%n", formatValue(obj, 0));
      return;
    }

    System.out.println("=== Object Inspection ===");
    System.out.printf("Type: %s%n", cls.getName());
    System.out.printf("Hash: %s%n", Integer.toHexString(obj.hashCode()));

    // Show class hierarchy
    List<Class<?>> hierarchy = getClassHierarchy(cls);
    if (hierarchy.size() > 1) {
      System.out
          .println("Hierarchy: " + hierarchy.stream().map(Class::getSimpleName).collect(Collectors.joining(" → ")));
    }

    // Show interfaces
    Class<?>[] interfaces = cls.getInterfaces();
    if (interfaces.length > 0) {
      System.out.println(
          "Interfaces: " + Arrays.stream(interfaces).map(Class::getSimpleName).collect(Collectors.joining(", ")));
    }

    // Show fields with values
    System.out.println("\n--- Fields ---");
    showFields(obj, false);

    // Show method summary
    System.out.println("\n--- Methods Summary ---");
    Map<String, List<Method>> methodsByName = Arrays.stream(cls.getMethods())
        .filter(m -> !m.getDeclaringClass().equals(Object.class)).collect(Collectors.groupingBy(Method::getName));

    methodsByName.forEach((name, methods) -> {
      if (methods.size() == 1) {
        System.out.printf("  %s%n", formatMethod(methods.get(0)));
      } else {
        System.out.printf("  %s (%d overloads)%n", name, methods.size());
      }
    });

    // Special handling for collections, arrays, maps
    if (obj instanceof Collection<?>) {
      System.out.printf("\n--- Collection Content (size=%d) ---%n", ((Collection<?>) obj).size());
      showCollection((Collection<?>) obj, DEFAULT_COLLECTION_LIMIT);
    } else if (obj instanceof Map<?, ?>) {
      System.out.printf("\n--- Map Content (size=%d) ---%n", ((Map<?, ?>) obj).size());
      showMap((Map<?, ?>) obj, DEFAULT_COLLECTION_LIMIT);
    } else if (obj.getClass().isArray()) {
      System.out.printf("\n--- Array Content (length=%d) ---%n", Array.getLength(obj));
      showArray(obj, DEFAULT_COLLECTION_LIMIT);
    }
  }

  /**
   * Show all fields of an object with their current values.
   */
  public static void fields(Object obj) {
    fields(obj, true);
  }

  /**
   * Show fields of an object with control over private field visibility.
   */
  public static void fields(Object obj, boolean includePrivate) {
    if (obj == null) {
      System.out.println("null");
      return;
    }

    System.out.printf("=== Fields of %s ===%n", obj.getClass().getSimpleName());
    showFields(obj, true, includePrivate);
  }

  /**
   * Show all methods of a class with signatures.
   */
  public static void methods(Class<?> cls) {
    if (cls == null) {
      System.out.println("null");
      return;
    }

    System.out.printf("=== Methods of %s ===%n", cls.getSimpleName());

    Method[] methods = cls.getMethods();
    Map<String, List<Method>> grouped = Arrays.stream(methods).filter(m -> !m.getDeclaringClass().equals(Object.class))
        .sorted(Comparator.comparing(Method::getName)).collect(
            Collectors.groupingBy(m -> m.getDeclaringClass().getSimpleName(), LinkedHashMap::new, Collectors.toList()));

    grouped.forEach((declaringClass, methodList) -> {
      System.out.printf("\nFrom %s:%n", declaringClass);
      methodList.forEach(m -> System.out.printf("  %s%n", formatMethodDetailed(m)));
    });
  }

  /**
   * Trace object graph to specified depth. Useful for understanding complex
   * object relationships.
   */
  public static void trace(Object obj, int depth) {
    if (obj == null) {
      System.out.println("null");
      return;
    }

    System.out.printf("=== Object Graph Trace (depth=%d) ===%n", depth);
    Set<Integer> visited = new HashSet<>();
    traceRecursive(obj, 0, depth, "", visited);
  }

  /**
   * Show collection contents with pagination.
   */
  public static void show(Collection<?> collection, int limit) {
    if (collection == null) {
      System.out.println("null");
      return;
    }

    System.out.printf("=== Collection (%s, size=%d) ===%n", collection.getClass().getSimpleName(), collection.size());
    showCollection(collection, limit);
  }

  /**
   * Show map contents with pagination.
   */
  public static void show(Map<?, ?> map, int limit) {
    if (map == null) {
      System.out.println("null");
      return;
    }

    System.out.printf("=== Map (%s, size=%d) ===%n", map.getClass().getSimpleName(), map.size());
    showMap(map, limit);
  }

  /**
   * Show array contents with pagination.
   */
  public static void show(Object array, int limit) {
    if (array == null) {
      System.out.println("null");
      return;
    }

    if (!array.getClass().isArray()) {
      System.out.println("Not an array: " + array.getClass().getName());
      return;
    }

    System.out.printf("=== Array (%s, length=%d) ===%n", array.getClass().getComponentType().getSimpleName(),
        Array.getLength(array));
    showArray(array, limit);
  }

  /**
   * Quick type information for an object.
   */
  public static void type(Object obj) {
    if (obj == null) {
      System.out.println("null");
      return;
    }

    Class<?> cls = obj.getClass();
    System.out.printf("Type: %s%n", cls.getName());
    System.out.printf("Package: %s%n", cls.getPackage() != null ? cls.getPackage().getName() : "default");
    System.out.printf("Module: %s%n", cls.getModule().getName());
    System.out.printf("ClassLoader: %s%n", cls.getClassLoader());

    // Show generic type information if available
    Type genericSuper = cls.getGenericSuperclass();
    if (genericSuper instanceof ParameterizedType) {
      System.out.printf("Generic Superclass: %s%n", genericSuper.getTypeName());
    }

    Type[] genericInterfaces = cls.getGenericInterfaces();
    if (genericInterfaces.length > 0) {
      System.out.println("Generic Interfaces:");
      for (Type t : genericInterfaces) {
        System.out.printf("  %s%n", t.getTypeName());
      }
    }
  }

  /**
   * Find fields by name pattern (case-insensitive substring match).
   */
  public static void findFields(Class<?> cls, String pattern) {
    if (cls == null || pattern == null) {
      return;
    }

    String lowerPattern = pattern.toLowerCase();
    System.out.printf("=== Fields matching '%s' in %s ===%n", pattern, cls.getSimpleName());

    Arrays.stream(cls.getFields()).filter(f -> f.getName().toLowerCase().contains(lowerPattern))
        .forEach(f -> System.out.printf("  %s %s%n", Modifier.toString(f.getModifiers()), formatField(f)));
  }

  /**
   * Find methods by name pattern (case-insensitive substring match).
   */
  public static void findMethods(Class<?> cls, String pattern) {
    if (cls == null || pattern == null) {
      return;
    }

    String lowerPattern = pattern.toLowerCase();
    System.out.printf("=== Methods matching '%s' in %s ===%n", pattern, cls.getSimpleName());

    Arrays.stream(cls.getMethods()).filter(m -> m.getName().toLowerCase().contains(lowerPattern))
        .forEach(m -> System.out.printf("  %s%n", formatMethodDetailed(m)));
  }

  /**
   * Compare two objects and show their differences. Critical for debugging -
   * compare expected vs actual state.
   */
  public static void diff(Object obj1, Object obj2) {
    if (obj1 == null && obj2 == null) {
      System.out.println("Both objects are null - no differences");
      return;
    }

    if (obj1 == null) {
      System.out.println("DIFFERENCE: obj1 is null, obj2 is " + obj2.getClass().getSimpleName());
      return;
    }

    if (obj2 == null) {
      System.out.println("DIFFERENCE: obj1 is " + obj1.getClass().getSimpleName() + ", obj2 is null");
      return;
    }

    Class<?> cls1 = obj1.getClass();
    Class<?> cls2 = obj2.getClass();

    System.out.println("=== Object Comparison ===");
    System.out.printf("obj1: %s@%x%n", cls1.getSimpleName(), obj1.hashCode());
    System.out.printf("obj2: %s@%x%n", cls2.getSimpleName(), obj2.hashCode());

    if (!cls1.equals(cls2)) {
      System.out.println("DIFFERENCE: Different types!");
      System.out.printf("  obj1 type: %s%n", cls1.getName());
      System.out.printf("  obj2 type: %s%n", cls2.getName());
      return;
    }

    // Check if they're the same object
    if (obj1 == obj2) {
      System.out.println("Same object reference - no differences");
      return;
    }

    // Special handling for collections
    if (obj1 instanceof Collection<?> c1 && obj2 instanceof Collection<?> c2) {
      diffCollections(c1, c2);
      return;
    }

    // Special handling for maps
    if (obj1 instanceof Map<?, ?> m1 && obj2 instanceof Map<?, ?> m2) {
      diffMaps(m1, m2);
      return;
    }

    // Special handling for arrays
    if (cls1.isArray()) {
      diffArrays(obj1, obj2);
      return;
    }

    // Field-by-field comparison
    diffFields(obj1, obj2, cls1);
  }

  /**
   * Tree-like visualization of object graph. Better than trace() for
   * understanding object relationships.
   */
  public static void tree(Object obj) {
    tree(obj, DEFAULT_DEPTH);
  }

  /**
   * Tree-like visualization with custom depth.
   */
  public static void tree(Object obj, int maxDepth) {
    if (obj == null) {
      System.out.println("null");
      return;
    }

    System.out.println("=== Object Tree ===");
    Set<Integer> visited = new HashSet<>();
    treeRecursive(obj, 0, maxDepth, "", visited, true);
  }

  // ========== Helper Methods ==========

  private static void showFields(Object obj, boolean includeInherited) {
    showFields(obj, includeInherited, true);
  }

  private static void showFields(Object obj, boolean includeInherited, boolean includePrivate) {
    Class<?> cls = obj.getClass();
    List<Field> fields = new ArrayList<>();

    if (includeInherited) {
      // Get all fields including inherited
      while (cls != null && !cls.equals(Object.class)) {
        fields.addAll(0, Arrays.asList(cls.getDeclaredFields()));
        cls = cls.getSuperclass();
      }
    } else {
      // Only declared fields
      fields.addAll(Arrays.asList(obj.getClass().getDeclaredFields()));
    }

    for (Field field : fields) {
      // Filter private fields if not included
      if (!includePrivate && Modifier.isPrivate(field.getModifiers())) {
        continue;
      }

      try {
        field.setAccessible(true);
        Object value = field.get(obj);
        String valueStr = formatValue(value, 0);
        System.out.printf("  %s %s = %s%n", Modifier.toString(field.getModifiers()), formatField(field), valueStr);
      } catch (Exception e) {
        // Handle both IllegalAccessException and InaccessibleObjectException
        System.out.printf("  %s %s = <inaccessible>%n", Modifier.toString(field.getModifiers()), formatField(field));
      }
    }
  }

  private static void showCollection(Collection<?> collection, int limit) {
    int count = 0;
    for (Object item : collection) {
      if (count >= limit) {
        System.out.printf("  ... and %d more%n", collection.size() - count);
        break;
      }
      System.out.printf("  [%d] %s%n", count, formatValue(item, 1));
      count++;
    }
  }

  private static void showMap(Map<?, ?> map, int limit) {
    int count = 0;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (count >= limit) {
        System.out.printf("  ... and %d more entries%n", map.size() - count);
        break;
      }
      System.out.printf("  %s → %s%n", formatValue(entry.getKey(), 1), formatValue(entry.getValue(), 1));
      count++;
    }
  }

  private static void showArray(Object array, int limit) {
    int length = Array.getLength(array);
    int show = Math.min(length, limit);

    for (int i = 0; i < show; i++) {
      Object element = Array.get(array, i);
      System.out.printf("  [%d] %s%n", i, formatValue(element, 1));
    }

    if (length > limit) {
      System.out.printf("  ... and %d more%n", length - limit);
    }
  }

  private static void traceRecursive(Object obj, int currentDepth, int maxDepth, String indent, Set<Integer> visited) {
    if (obj == null || currentDepth > maxDepth) {
      return;
    }

    int objHash = System.identityHashCode(obj);
    if (visited.contains(objHash)) {
      System.out.printf("%s<circular reference to %s@%x>%n", indent, obj.getClass().getSimpleName(), objHash);
      return;
    }
    visited.add(objHash);

    Class<?> cls = obj.getClass();
    System.out.printf("%s%s@%x%n", indent, cls.getSimpleName(), objHash);

    if (isPrimitiveOrWrapper(cls) || currentDepth == maxDepth) {
      System.out.printf("%s  value: %s%n", indent, formatValue(obj, 0));
      return;
    }

    // Trace fields
    Field[] fields = cls.getDeclaredFields();
    for (Field field : fields) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }

      try {
        field.setAccessible(true);
        Object fieldValue = field.get(obj);
        System.out.printf("%s  %s: ", indent, field.getName());

        if (fieldValue == null) {
          System.out.println("null");
        } else if (isPrimitiveOrWrapper(fieldValue.getClass())) {
          System.out.println(formatValue(fieldValue, 0));
        } else {
          System.out.println();
          traceRecursive(fieldValue, currentDepth + 1, maxDepth, indent + "    ", visited);
        }
      } catch (Exception e) {
        // Handle both IllegalAccessException and InaccessibleObjectException
        System.out.printf("%s  %s: <inaccessible>%n", indent, field.getName());
      }
    }
  }

  private static String formatValue(Object value, int depth) {
    if (value == null) {
      return "null";
    }

    Class<?> cls = value.getClass();

    if (cls == String.class) {
      String str = (String) value;
      if (str.length() > MAX_STRING_LENGTH) {
        return "\"" + str.substring(0, MAX_STRING_LENGTH) + "...\" (len=" + str.length() + ")";
      }
      return "\"" + str + "\"";
    }

    if (isPrimitiveOrWrapper(cls)) {
      return value.toString();
    }

    if (cls.isArray()) {
      return String.format("%s[%d]", cls.getComponentType().getSimpleName(), Array.getLength(value));
    }

    if (value instanceof Collection<?>) {
      return String.format("%s(size=%d)", cls.getSimpleName(), ((Collection<?>) value).size());
    }

    if (value instanceof Map<?, ?>) {
      return String.format("%s(size=%d)", cls.getSimpleName(), ((Map<?, ?>) value).size());
    }

    // For other objects, show class and hashcode
    return String.format("%s@%x", cls.getSimpleName(), value.hashCode());
  }

  private static String formatField(Field field) {
    return String.format("%s %s", field.getType().getSimpleName(), field.getName());
  }

  private static String formatMethod(Method method) {
    String params = Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
        .collect(Collectors.joining(", "));
    return String.format("%s(%s): %s", method.getName(), params, method.getReturnType().getSimpleName());
  }

  private static String formatMethodDetailed(Method method) {
    String modifiers = Modifier.toString(method.getModifiers());
    String params = Arrays.stream(method.getParameters()).map(p -> p.getType().getSimpleName() + " " + p.getName())
        .collect(Collectors.joining(", "));
    String exceptions = method.getExceptionTypes().length > 0
        ? " throws "
            + Arrays.stream(method.getExceptionTypes()).map(Class::getSimpleName).collect(Collectors.joining(", "))
        : "";

    return String.format("%s %s %s(%s)%s", modifiers, method.getReturnType().getSimpleName(), method.getName(), params,
        exceptions);
  }

  private static boolean isPrimitiveOrWrapper(Class<?> cls) {
    return cls.isPrimitive() || PRIMITIVE_WRAPPERS.contains(cls);
  }

  private static List<Class<?>> getClassHierarchy(Class<?> cls) {
    List<Class<?>> hierarchy = new ArrayList<>();
    while (cls != null && !cls.equals(Object.class)) {
      hierarchy.add(0, cls);
      cls = cls.getSuperclass();
    }
    return hierarchy;
  }

  // ========== Diff Helper Methods ==========

  private static void diffCollections(Collection<?> c1, Collection<?> c2) {
    System.out.printf("Collection sizes: %d vs %d%n", c1.size(), c2.size());

    if (c1.size() != c2.size()) {
      System.out.printf("DIFFERENCE: Size mismatch (%d vs %d)%n", c1.size(), c2.size());
    }

    List<?> list1 = new ArrayList<>(c1);
    List<?> list2 = new ArrayList<>(c2);

    int maxSize = Math.max(list1.size(), list2.size());
    boolean hasDifferences = false;

    for (int i = 0; i < maxSize; i++) {
      Object item1 = i < list1.size() ? list1.get(i) : "<missing>";
      Object item2 = i < list2.size() ? list2.get(i) : "<missing>";

      if (!Objects.equals(item1, item2)) {
        if (!hasDifferences) {
          System.out.println("DIFFERENCES:");
          hasDifferences = true;
        }
        System.out.printf("  [%d]: %s vs %s%n", i, formatValue(item1, 0), formatValue(item2, 0));
      }
    }

    if (!hasDifferences) {
      System.out.println("Collections are equivalent");
    }
  }

  private static void diffMaps(Map<?, ?> m1, Map<?, ?> m2) {
    System.out.printf("Map sizes: %d vs %d%n", m1.size(), m2.size());

    if (m1.size() != m2.size()) {
      System.out.printf("DIFFERENCE: Size mismatch (%d vs %d)%n", m1.size(), m2.size());
    }

    Set<Object> allKeys = new HashSet<>();
    allKeys.addAll(m1.keySet());
    allKeys.addAll(m2.keySet());

    boolean hasDifferences = false;

    for (Object key : allKeys) {
      Object val1 = m1.get(key);
      Object val2 = m2.get(key);

      if (!Objects.equals(val1, val2)) {
        if (!hasDifferences) {
          System.out.println("DIFFERENCES:");
          hasDifferences = true;
        }

        if (!m1.containsKey(key)) {
          System.out.printf("  %s: <missing> vs %s%n", key, formatValue(val2, 0));
        } else if (!m2.containsKey(key)) {
          System.out.printf("  %s: %s vs <missing>%n", key, formatValue(val1, 0));
        } else {
          System.out.printf("  %s: %s vs %s%n", key, formatValue(val1, 0), formatValue(val2, 0));
        }
      }
    }

    if (!hasDifferences) {
      System.out.println("Maps are equivalent");
    }
  }

  private static void diffArrays(Object arr1, Object arr2) {
    int len1 = Array.getLength(arr1);
    int len2 = Array.getLength(arr2);

    System.out.printf("Array lengths: %d vs %d%n", len1, len2);

    if (len1 != len2) {
      System.out.printf("DIFFERENCE: Length mismatch (%d vs %d)%n", len1, len2);
    }

    int maxLen = Math.max(len1, len2);
    boolean hasDifferences = false;

    for (int i = 0; i < maxLen; i++) {
      Object item1 = i < len1 ? Array.get(arr1, i) : "<missing>";
      Object item2 = i < len2 ? Array.get(arr2, i) : "<missing>";

      if (!Objects.equals(item1, item2)) {
        if (!hasDifferences) {
          System.out.println("DIFFERENCES:");
          hasDifferences = true;
        }
        System.out.printf("  [%d]: %s vs %s%n", i, formatValue(item1, 0), formatValue(item2, 0));
      }
    }

    if (!hasDifferences) {
      System.out.println("Arrays are equivalent");
    }
  }

  private static void diffFields(Object obj1, Object obj2, Class<?> cls) {
    Field[] fields = cls.getDeclaredFields();
    boolean hasDifferences = false;

    for (Field field : fields) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }

      try {
        field.setAccessible(true);
        Object val1 = field.get(obj1);
        Object val2 = field.get(obj2);

        if (!Objects.equals(val1, val2)) {
          if (!hasDifferences) {
            System.out.println("DIFFERENCES:");
            hasDifferences = true;
          }
          System.out.printf("  %s: %s vs %s%n", field.getName(), formatValue(val1, 0), formatValue(val2, 0));
        }
      } catch (Exception e) {
        System.out.printf("  %s: <inaccessible>%n", field.getName());
      }
    }

    if (!hasDifferences) {
      System.out.println("Objects are equivalent (by field comparison)");
    }
  }

  // ========== Tree Helper Methods ==========

  private static void treeRecursive(Object obj, int currentDepth, int maxDepth, String indent, Set<Integer> visited,
      boolean isLast) {
    if (obj == null || currentDepth > maxDepth) {
      return;
    }

    int objHash = System.identityHashCode(obj);
    String connector = isLast ? "└── " : "├── ";

    if (visited.contains(objHash)) {
      System.out.printf("%s%s%s@%x (circular)%n", indent, connector, obj.getClass().getSimpleName(), objHash);
      return;
    }

    visited.add(objHash);
    Class<?> cls = obj.getClass();

    // Show the object node
    System.out.printf("%s%s%s@%x", indent, connector, cls.getSimpleName(), objHash);

    // Add value for primitives/strings
    if (isPrimitiveOrWrapper(cls)) {
      System.out.printf(" = %s", formatValue(obj, 0));
    }
    System.out.println();

    if (currentDepth == maxDepth) {
      return;
    }

    String childIndent = indent + (isLast ? "    " : "│   ");

    // Special handling for collections
    if (obj instanceof Collection<?> c && !c.isEmpty()) {
      int limit = Math.min(c.size(), 5); // Limit for tree display

      List<?> items = c instanceof List<?> ? (List<?>) c : new ArrayList<>(c);
      for (int i = 0; i < limit; i++) {
        boolean childIsLast = (i == limit - 1) && (limit == c.size());
        System.out.printf("%s%s[%d]%n", childIndent, childIsLast ? "└── " : "├── ", i);
        treeRecursive(items.get(i), currentDepth + 1, maxDepth, childIndent + (childIsLast ? "    " : "│   "), visited,
            true);
      }

      if (c.size() > limit) {
        System.out.printf("%s└── ... and %d more%n", childIndent, c.size() - limit);
      }
      return;
    }

    // Special handling for maps
    if (obj instanceof Map<?, ?> m && !m.isEmpty()) {
      int count = 0;
      int limit = Math.min(m.size(), 5);

      for (var entry : m.entrySet()) {
        if (count >= limit)
          break;
        boolean childIsLast = (count == limit - 1) && (limit == m.size());
        System.out.printf("%s%s%s%n", childIndent, childIsLast ? "└── " : "├── ", entry.getKey());
        treeRecursive(entry.getValue(), currentDepth + 1, maxDepth, childIndent + (childIsLast ? "    " : "│   "),
            visited, true);
        count++;
      }

      if (m.size() > limit) {
        System.out.printf("%s└── ... and %d more%n", childIndent, m.size() - limit);
      }
      return;
    }

    // Field traversal
    Field[] fields = cls.getDeclaredFields();
    List<Field> accessibleFields = new ArrayList<>();

    for (Field field : fields) {
      if (!Modifier.isStatic(field.getModifiers())) {
        try {
          field.setAccessible(true);
          Object value = field.get(obj);
          if (value != null && !isPrimitiveOrWrapper(value.getClass())) {
            accessibleFields.add(field);
          }
        } catch (Exception ignored) {
        }
      }
    }

    for (int i = 0; i < Math.min(accessibleFields.size(), 5); i++) {
      Field field = accessibleFields.get(i);
      boolean childIsLast = i == Math.min(accessibleFields.size(), 5) - 1;

      try {
        Object value = field.get(obj);
        System.out.printf("%s%s%s%n", childIndent, childIsLast ? "└── " : "├── ", field.getName());
        treeRecursive(value, currentDepth + 1, maxDepth, childIndent + (childIsLast ? "    " : "│   "), visited, true);
      } catch (Exception ignored) {
      }
    }
  }
}