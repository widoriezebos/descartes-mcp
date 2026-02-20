package com.bitsapplied.descartes.debugger.evaluation;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.codehaus.janino.Java;
import org.codehaus.janino.Parser;
import org.codehaus.janino.Scanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bitsapplied.descartes.debugger.exceptions.DebuggerErrorCode;
import com.bitsapplied.descartes.debugger.exceptions.DebuggerException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

/**
 * Evaluates Java expressions remotely in the debuggee JVM via JDI/JDWP.
 *
 * <p>
 * Uses Janino's {@link Parser#parseExpression()} to parse expressions into an
 * AST ({@link Java.Rvalue} nodes), then walks the AST and executes each
 * operation remotely via JDI. This enables expression evaluation in proxy mode
 * where the proxy JVM lacks the debuggee's classes.
 *
 * <p>
 * Supported expression types:
 * <ul>
 * <li>Literals (integer, long, float, double, boolean, char, string, null)</li>
 * <li>Local variables and parameters</li>
 * <li>{@code this} reference</li>
 * <li>Field access ({@code obj.field})</li>
 * <li>Method invocation ({@code obj.method(args)})</li>
 * <li>Array access and length ({@code arr[i]}, {@code arr.length})</li>
 * <li>Arithmetic and comparison operators</li>
 * <li>Boolean operators ({@code &&}, {@code ||}) with short-circuit</li>
 * <li>String concatenation ({@code +} with String operand)</li>
 * <li>Ternary ({@code cond ? a : b})</li>
 * <li>{@code instanceof} and casts</li>
 * <li>{@code new} object creation</li>
 * <li>Parenthesized expressions</li>
 * <li>Unary operators ({@code !}, {@code -}, {@code ~}, {@code +})</li>
 * </ul>
 *
 * <p>
 * Out of scope: lambdas, method references, assignments, {@code ++}/{@code --},
 * multi-statement blocks.
 */
public class JdiRemoteEvaluator {
  private static final Logger logger = LoggerFactory.getLogger(JdiRemoteEvaluator.class);

  /**
   * Evaluates an expression in the context of a suspended stack frame.
   *
   * @param expression the Java expression to evaluate
   * @param frame      the stack frame providing variable context (thread must be
   *                   suspended)
   * @return the result formatted as a string
   * @throws DebuggerException if parsing or evaluation fails
   */
  public String evaluate(String expression, StackFrame frame) {
    try {
      Java.Rvalue ast = parseExpression(expression);
      ThreadReference thread = frame.thread();
      Value result = interpretNode(ast, frame, thread);
      return formatValue(result, thread);
    } catch (DebuggerException e) {
      throw e;
    } catch (Exception e) {
      logger.debug("JDI remote evaluation failed for '{}': {}", expression, e.getMessage());
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "JDI remote evaluation failed: " + e.getMessage(), e);
    }
  }

  /**
   * Parses an expression string into a Janino AST node.
   */
  static Java.Rvalue parseExpression(String expression) {
    try {
      Scanner scanner = new Scanner(null, new StringReader(expression));
      Parser parser = new Parser(scanner);
      return parser.parseExpression();
    } catch (Exception e) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_COMPILATION_FAILED,
          "Failed to parse expression: " + e.getMessage(), e);
    }
  }

  // ========== AST Interpretation ==========

  private Value interpretNode(Java.Rvalue node, StackFrame frame, ThreadReference thread) throws Exception {
    if (node instanceof Java.ParenthesizedExpression paren) {
      return interpretNode(paren.value, frame, thread);
    }

    if (node instanceof Java.IntegerLiteral lit) {
      return interpretIntegerLiteral(lit, frame);
    }
    if (node instanceof Java.FloatingPointLiteral lit) {
      return interpretFloatingPointLiteral(lit, frame);
    }
    if (node instanceof Java.BooleanLiteral lit) {
      return vm(frame).mirrorOf(Boolean.parseBoolean(lit.value));
    }
    if (node instanceof Java.CharacterLiteral lit) {
      return vm(frame).mirrorOf(parseCharLiteral(lit.value));
    }
    if (node instanceof Java.StringLiteral lit) {
      return vm(frame).mirrorOf(parseStringLiteral(lit.value));
    }
    if (node instanceof Java.NullLiteral) {
      return null;
    }

    if (node instanceof Java.AmbiguousName ambig) {
      return resolveAmbiguousName(ambig, frame, thread);
    }
    if (node instanceof Java.ThisReference) {
      return frame.thisObject();
    }

    if (node instanceof Java.FieldAccessExpression fieldAccess) {
      return interpretFieldAccess(fieldAccess, frame, thread);
    }

    if (node instanceof Java.MethodInvocation invocation) {
      return interpretMethodInvocation(invocation, frame, thread);
    }

    if (node instanceof Java.ArrayAccessExpression arrayAccess) {
      return interpretArrayAccess(arrayAccess, frame, thread);
    }

    if (node instanceof Java.BinaryOperation binOp) {
      return interpretBinaryOperation(binOp, frame, thread);
    }

    if (node instanceof Java.UnaryOperation unaryOp) {
      return interpretUnaryOperation(unaryOp, frame, thread);
    }

    if (node instanceof Java.ConditionalExpression ternary) {
      return interpretTernary(ternary, frame, thread);
    }

    if (node instanceof Java.Cast cast) {
      return interpretCast(cast, frame, thread);
    }

    if (node instanceof Java.Instanceof instanceOf) {
      return interpretInstanceof(instanceOf, frame, thread);
    }

    if (node instanceof Java.NewClassInstance newInst) {
      return interpretNewClassInstance(newInst, frame, thread);
    }

    if (node instanceof Java.ArrayLength arrayLen) {
      return interpretArrayLength(arrayLen, frame, thread);
    }

    if (node instanceof Java.SuperclassFieldAccessExpression superField) {
      ObjectReference thisObj = frame.thisObject();
      if (thisObj == null) {
        throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
            "Cannot access super field in static context");
      }
      Field field = thisObj.referenceType().fieldByName(superField.fieldName);
      if (field == null) {
        throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
            "Field not found: " + superField.fieldName);
      }
      return thisObj.getValue(field);
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Unsupported expression type: " + node.getClass().getSimpleName());
  }

  // ========== Literal Interpretation ==========

  private Value interpretIntegerLiteral(Java.IntegerLiteral lit, StackFrame frame) {
    String raw = lit.value;
    VirtualMachine vm = vm(frame);

    // Remove underscores (Java 7+)
    raw = raw.replace("_", "");

    if (raw.endsWith("L") || raw.endsWith("l")) {
      return vm.mirrorOf(Long.decode(raw.substring(0, raw.length() - 1)));
    }

    // Try parsing as int first, fall back to long for large values
    try {
      return vm.mirrorOf(Integer.decode(raw));
    } catch (NumberFormatException e) {
      return vm.mirrorOf(Long.decode(raw));
    }
  }

  private Value interpretFloatingPointLiteral(Java.FloatingPointLiteral lit, StackFrame frame) {
    String raw = lit.value.replace("_", "");
    VirtualMachine vm = vm(frame);

    if (raw.endsWith("f") || raw.endsWith("F")) {
      return vm.mirrorOf(Float.parseFloat(raw));
    }
    // Default to double
    String cleaned = raw;
    if (cleaned.endsWith("d") || cleaned.endsWith("D")) {
      cleaned = cleaned.substring(0, cleaned.length() - 1);
    }
    return vm.mirrorOf(Double.parseDouble(cleaned));
  }

  private char parseCharLiteral(String literal) {
    // Remove surrounding single quotes
    String inner = literal.substring(1, literal.length() - 1);
    if (inner.length() == 1) {
      return inner.charAt(0);
    }
    if (inner.startsWith("\\")) {
      return switch (inner.charAt(1)) {
      case 'n' -> '\n';
      case 't' -> '\t';
      case 'r' -> '\r';
      case '\\' -> '\\';
      case '\'' -> '\'';
      case '"' -> '"';
      case 'b' -> '\b';
      case 'f' -> '\f';
      case '0' -> '\0';
      default -> {
        // Unicode escape or octal
        if (inner.charAt(1) == 'u') {
          yield (char) Integer.parseInt(inner.substring(2), 16);
        }
        yield (char) Integer.parseInt(inner.substring(1), 8);
      }
      };
    }
    return inner.charAt(0);
  }

  private String parseStringLiteral(String literal) {
    // Remove surrounding double quotes
    String inner = literal.substring(1, literal.length() - 1);
    StringBuilder sb = new StringBuilder(inner.length());
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (c == '\\' && i + 1 < inner.length()) {
        i++;
        char next = inner.charAt(i);
        switch (next) {
        case 'n' -> sb.append('\n');
        case 't' -> sb.append('\t');
        case 'r' -> sb.append('\r');
        case '\\' -> sb.append('\\');
        case '"' -> sb.append('"');
        case '\'' -> sb.append('\'');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case '0' -> sb.append('\0');
        case 'u' -> {
          if (i + 4 < inner.length()) {
            sb.append((char) Integer.parseInt(inner.substring(i + 1, i + 5), 16));
            i += 4;
          }
        }
        default -> {
          sb.append('\\');
          sb.append(next);
        }
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  // ========== Name Resolution ==========

  private Value resolveAmbiguousName(Java.AmbiguousName ambig, StackFrame frame, ThreadReference thread)
      throws Exception {
    String[] identifiers = ambig.identifiers;
    int count = ambig.n;
    // Start with the first identifier
    Value current = resolveIdentifier(identifiers[0], frame);

    // Follow the chain (use n to know how many identifiers to resolve)
    for (int i = 1; i < count; i++) {
      current = resolveFieldOnValue(current, identifiers[i], frame, thread);
    }
    return current;
  }

  private Value resolveIdentifier(String name, StackFrame frame) {
    // Try local variable first
    try {
      LocalVariable localVar = frame.visibleVariableByName(name);
      if (localVar != null) {
        return frame.getValue(localVar);
      }
    } catch (Exception e) {
      logger.trace("Failed to look up local variable '{}': {}", name, e.getMessage());
    }

    // Try 'this' fields
    ObjectReference thisObj = frame.thisObject();
    if (thisObj != null) {
      Field field = thisObj.referenceType().fieldByName(name);
      if (field != null) {
        return thisObj.getValue(field);
      }
    }

    // Try static fields on the declaring type
    try {
      ReferenceType declaringType = frame.location().declaringType();
      Field staticField = declaringType.fieldByName(name);
      if (staticField != null && staticField.isStatic()) {
        return declaringType.getValue(staticField);
      }
    } catch (Exception e) {
      logger.trace("Failed static field lookup for '{}': {}", name, e.getMessage());
    }

    // Try as a class name (for static access like System.out)
    VirtualMachine vm = vm(frame);
    List<ReferenceType> types = vm.classesByName(name);
    if (!types.isEmpty()) {
      return types.get(0).classObject();
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Cannot resolve identifier: " + name);
  }

  private Value resolveFieldOnValue(Value target, String fieldName, StackFrame frame, ThreadReference thread)
      throws Exception {
    if (target == null) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Cannot access field '" + fieldName + "' on null");
    }

    // Handle array.length
    if (target instanceof ArrayReference arr && "length".equals(fieldName)) {
      return vm(frame).mirrorOf(arr.length());
    }

    if (target instanceof ObjectReference objRef) {
      // Handle Class objects for static field access (e.g., System.out)
      if (isClassObject(objRef)) {
        ReferenceType classType = getClassFromClassObject(objRef, frame);
        if (classType != null) {
          Field staticField = classType.fieldByName(fieldName);
          if (staticField != null) {
            return classType.getValue(staticField);
          }
        }
      }

      Field field = objRef.referenceType().fieldByName(fieldName);
      if (field != null) {
        return objRef.getValue(field);
      }

      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Field '" + fieldName + "' not found on " + objRef.referenceType().name());
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Cannot access field on primitive value");
  }

  // ========== Field Access ==========

  private Value interpretFieldAccess(Java.FieldAccessExpression fieldAccess, StackFrame frame, ThreadReference thread)
      throws Exception {
    Value target = interpretAtom(fieldAccess.lhs, frame, thread);
    return resolveFieldOnValue(target, fieldAccess.fieldName, frame, thread);
  }

  private Value interpretAtom(Java.Atom atom, StackFrame frame, ThreadReference thread) throws Exception {
    if (atom instanceof Java.Rvalue rv) {
      return interpretNode(rv, frame, thread);
    }
    // Atom that's not an Rvalue (e.g., a type reference) — shouldn't happen in
    // field access, but handle gracefully
    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Cannot evaluate atom: " + atom.getClass().getSimpleName());
  }

  // ========== Method Invocation ==========

  private Value interpretMethodInvocation(Java.MethodInvocation invocation, StackFrame frame, ThreadReference thread)
      throws Exception {
    String methodName = invocation.methodName;
    Java.Rvalue[] argNodes = invocation.arguments;

    // Evaluate arguments
    List<Value> argValues = new ArrayList<>();
    for (Java.Rvalue arg : argNodes) {
      argValues.add(interpretNode(arg, frame, thread));
    }

    // Determine target
    Value target;
    if (invocation.target != null) {
      target = interpretAtom(invocation.target, frame, thread);
    } else {
      // No explicit target — try 'this'
      target = frame.thisObject();
      if (target == null) {
        // Static context — try the declaring type
        ReferenceType declaringType = frame.location().declaringType();
        return invokeStaticMethod(declaringType, methodName, argValues, thread, frame);
      }
    }

    if (target == null) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Cannot invoke method '" + methodName + "' on null");
    }

    // Handle Class objects for static method calls
    if (isClassObject(target)) {
      ReferenceType classType = getClassFromClassObject((ObjectReference) target, frame);
      if (classType != null) {
        try {
          return invokeStaticMethod(classType, methodName, argValues, thread, frame);
        } catch (DebuggerException e) {
          // Fall through to try instance method on the Class object itself
        }
      }
    }

    if (target instanceof ObjectReference objRef) {
      return invokeInstanceMethod(objRef, methodName, argValues, thread);
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Cannot invoke method on primitive value");
  }

  private Value invokeInstanceMethod(ObjectReference obj, String methodName, List<Value> args, ThreadReference thread)
      throws Exception {
    ReferenceType type = obj.referenceType();
    Method method = resolveMethod(type, methodName, args);
    if (method == null) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Method '" + methodName + "' not found on " + type.name());
    }

    List<Value> coerced = coerceArguments(args, method, obj.virtualMachine());
    return obj.invokeMethod(thread, method, coerced, ObjectReference.INVOKE_SINGLE_THREADED);
  }

  private Value invokeStaticMethod(ReferenceType type, String methodName, List<Value> args, ThreadReference thread,
      StackFrame frame) throws Exception {
    Method method = resolveMethod(type, methodName, args);
    if (method == null) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Static method '" + methodName + "' not found on " + type.name());
    }

    if (type instanceof ClassType classType) {
      List<Value> coerced = coerceArguments(args, method, vm(frame));
      return classType.invokeMethod(thread, method, coerced, ObjectReference.INVOKE_SINGLE_THREADED);
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Cannot invoke static method on non-class type: " + type.name());
  }

  /**
   * Resolves a method by name and argument count, with basic type matching.
   */
  private Method resolveMethod(ReferenceType type, String methodName, List<Value> args) {
    List<Method> candidates = type.methodsByName(methodName);
    if (candidates.isEmpty()) {
      // Try superclass/interface methods
      if (type instanceof ClassType ct && ct.superclass() != null) {
        return resolveMethod(ct.superclass(), methodName, args);
      }
      return null;
    }

    // Filter by arity
    List<Method> arityMatch = candidates.stream()
        .filter(m -> m.argumentTypeNames().size() == args.size())
        .toList();

    if (arityMatch.size() == 1) {
      return arityMatch.get(0);
    }

    if (arityMatch.isEmpty()) {
      // Check for varargs
      List<Method> varargCandidates = candidates.stream()
          .filter(Method::isVarArgs)
          .filter(m -> m.argumentTypeNames().size() <= args.size() + 1)
          .toList();
      if (varargCandidates.size() == 1) {
        return varargCandidates.get(0);
      }
      // Fallback: return first match by name only
      return candidates.get(0);
    }

    // Multiple arity matches — try to pick the best one by type checking
    for (Method m : arityMatch) {
      if (argumentsMatchMethod(m, args)) {
        return m;
      }
    }

    // Fallback: first arity match
    return arityMatch.get(0);
  }

  private boolean argumentsMatchMethod(Method method, List<Value> args) {
    List<String> paramTypes = method.argumentTypeNames();
    for (int i = 0; i < args.size(); i++) {
      Value arg = args.get(i);
      String paramType = paramTypes.get(i);
      if (arg == null) {
        // null is compatible with any reference type
        if (isPrimitiveTypeName(paramType)) {
          return false;
        }
        continue;
      }
      // Basic type compatibility check
      String argType = arg.type().name();
      if (!isTypeCompatible(argType, paramType)) {
        return false;
      }
    }
    return true;
  }

  private boolean isTypeCompatible(String actual, String expected) {
    if (actual.equals(expected)) {
      return true;
    }
    // Primitive widening
    if (isPrimitiveTypeName(actual) && isPrimitiveTypeName(expected)) {
      return canWidenPrimitive(actual, expected);
    }
    // Object types — allow any for now (JDI will validate)
    return !isPrimitiveTypeName(actual) && !isPrimitiveTypeName(expected);
  }

  private boolean canWidenPrimitive(String from, String to) {
    return switch (from) {
    case "byte" -> List.of("short", "int", "long", "float", "double").contains(to);
    case "short" -> List.of("int", "long", "float", "double").contains(to);
    case "char" -> List.of("int", "long", "float", "double").contains(to);
    case "int" -> List.of("long", "float", "double").contains(to);
    case "long" -> List.of("float", "double").contains(to);
    case "float" -> "double".equals(to);
    default -> false;
    };
  }

  private boolean isPrimitiveTypeName(String typeName) {
    return switch (typeName) {
    case "byte", "short", "int", "long", "float", "double", "char", "boolean" -> true;
    default -> false;
    };
  }

  /**
   * Coerces arguments to match method parameter types (e.g., int → long widening).
   */
  private List<Value> coerceArguments(List<Value> args, Method method, VirtualMachine vm) {
    List<String> paramTypes;
    try {
      paramTypes = method.argumentTypeNames();
    } catch (Exception e) {
      return args;
    }

    if (paramTypes.size() != args.size()) {
      return args;
    }

    List<Value> coerced = new ArrayList<>(args.size());
    for (int i = 0; i < args.size(); i++) {
      Value arg = args.get(i);
      String paramType = paramTypes.get(i);
      coerced.add(coerceValue(arg, paramType, vm));
    }
    return coerced;
  }

  private Value coerceValue(Value value, String targetType, VirtualMachine vm) {
    if (value == null || !(value instanceof PrimitiveValue pv)) {
      return value;
    }

    return switch (targetType) {
    case "long" -> vm.mirrorOf(toLong(pv));
    case "double" -> vm.mirrorOf(toDouble(pv));
    case "float" -> vm.mirrorOf(toFloat(pv));
    case "int" -> vm.mirrorOf(toInt(pv));
    case "short" -> vm.mirrorOf((short) toInt(pv));
    case "byte" -> vm.mirrorOf((byte) toInt(pv));
    case "char" -> vm.mirrorOf((char) toInt(pv));
    default -> value;
    };
  }

  // ========== Array Access ==========

  private Value interpretArrayAccess(Java.ArrayAccessExpression arrayAccess, StackFrame frame, ThreadReference thread)
      throws Exception {
    Value arrayVal = interpretNode(arrayAccess.lhs, frame, thread);
    Value indexVal = interpretNode(arrayAccess.index, frame, thread);

    if (!(arrayVal instanceof ArrayReference arr)) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Array access on non-array value");
    }

    int index = toInt((PrimitiveValue) indexVal);
    if (index < 0 || index >= arr.length()) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Array index out of bounds: " + index + " (length: " + arr.length() + ")");
    }

    return arr.getValue(index);
  }

  private Value interpretArrayLength(Java.ArrayLength arrayLen, StackFrame frame, ThreadReference thread)
      throws Exception {
    Value arrayVal = interpretNode((Java.Rvalue) arrayLen.lhs, frame, thread);
    if (!(arrayVal instanceof ArrayReference arr)) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Cannot get length of non-array value");
    }
    return vm(frame).mirrorOf(arr.length());
  }

  // ========== Binary Operations ==========

  private Value interpretBinaryOperation(Java.BinaryOperation binOp, StackFrame frame, ThreadReference thread)
      throws Exception {
    String op = binOp.operator;

    // Short-circuit boolean operators
    if ("&&".equals(op)) {
      Value left = interpretNode(binOp.lhs, frame, thread);
      if (!toBoolean(left)) {
        return vm(frame).mirrorOf(false);
      }
      Value right = interpretNode(binOp.rhs, frame, thread);
      return vm(frame).mirrorOf(toBoolean(right));
    }
    if ("||".equals(op)) {
      Value left = interpretNode(binOp.lhs, frame, thread);
      if (toBoolean(left)) {
        return vm(frame).mirrorOf(true);
      }
      Value right = interpretNode(binOp.rhs, frame, thread);
      return vm(frame).mirrorOf(toBoolean(right));
    }

    Value left = interpretNode(binOp.lhs, frame, thread);
    Value right = interpretNode(binOp.rhs, frame, thread);

    // String concatenation
    if ("+".equals(op) && (isStringValue(left) || isStringValue(right))) {
      return interpretStringConcat(left, right, frame, thread);
    }

    // Null comparison
    if ("==".equals(op) && (left == null || right == null)) {
      return vm(frame).mirrorOf(left == right);
    }
    if ("!=".equals(op) && (left == null || right == null)) {
      return vm(frame).mirrorOf(left != right);
    }

    // Reference equality for objects
    if ("==".equals(op) && left instanceof ObjectReference && right instanceof ObjectReference) {
      return vm(frame).mirrorOf(((ObjectReference) left).uniqueID() == ((ObjectReference) right).uniqueID());
    }
    if ("!=".equals(op) && left instanceof ObjectReference && right instanceof ObjectReference) {
      return vm(frame).mirrorOf(((ObjectReference) left).uniqueID() != ((ObjectReference) right).uniqueID());
    }

    // Numeric operations
    if (left instanceof PrimitiveValue pLeft && right instanceof PrimitiveValue pRight) {
      return interpretPrimitiveBinaryOp(op, pLeft, pRight, frame);
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Cannot apply '" + op + "' to " + describeType(left) + " and " + describeType(right));
  }

  private Value interpretPrimitiveBinaryOp(String op, PrimitiveValue left, PrimitiveValue right, StackFrame frame) {
    VirtualMachine vm = vm(frame);

    // If either side is double/float, promote to double
    if (isFloatingPoint(left) || isFloatingPoint(right)) {
      double l = toDouble(left);
      double r = toDouble(right);
      return switch (op) {
      case "+" -> vm.mirrorOf(l + r);
      case "-" -> vm.mirrorOf(l - r);
      case "*" -> vm.mirrorOf(l * r);
      case "/" -> vm.mirrorOf(l / r);
      case "%" -> vm.mirrorOf(l % r);
      case "<" -> vm.mirrorOf(l < r);
      case ">" -> vm.mirrorOf(l > r);
      case "<=" -> vm.mirrorOf(l <= r);
      case ">=" -> vm.mirrorOf(l >= r);
      case "==" -> vm.mirrorOf(l == r);
      case "!=" -> vm.mirrorOf(l != r);
      default -> throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Unsupported operator for floating point: " + op);
      };
    }

    // Integer/long operations
    if (left instanceof LongValue || right instanceof LongValue) {
      long l = toLong(left);
      long r = toLong(right);
      return switch (op) {
      case "+" -> vm.mirrorOf(l + r);
      case "-" -> vm.mirrorOf(l - r);
      case "*" -> vm.mirrorOf(l * r);
      case "/" -> vm.mirrorOf(l / r);
      case "%" -> vm.mirrorOf(l % r);
      case "&" -> vm.mirrorOf(l & r);
      case "|" -> vm.mirrorOf(l | r);
      case "^" -> vm.mirrorOf(l ^ r);
      case "<<" -> vm.mirrorOf(l << r);
      case ">>" -> vm.mirrorOf(l >> r);
      case ">>>" -> vm.mirrorOf(l >>> r);
      case "<" -> vm.mirrorOf(l < r);
      case ">" -> vm.mirrorOf(l > r);
      case "<=" -> vm.mirrorOf(l <= r);
      case ">=" -> vm.mirrorOf(l >= r);
      case "==" -> vm.mirrorOf(l == r);
      case "!=" -> vm.mirrorOf(l != r);
      default -> throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Unsupported operator for long: " + op);
      };
    }

    // int operations (includes byte, short, char promoted to int)
    int l = toInt(left);
    int r = toInt(right);
    return switch (op) {
    case "+" -> vm.mirrorOf(l + r);
    case "-" -> vm.mirrorOf(l - r);
    case "*" -> vm.mirrorOf(l * r);
    case "/" -> vm.mirrorOf(l / r);
    case "%" -> vm.mirrorOf(l % r);
    case "&" -> vm.mirrorOf(l & r);
    case "|" -> vm.mirrorOf(l | r);
    case "^" -> vm.mirrorOf(l ^ r);
    case "<<" -> vm.mirrorOf(l << r);
    case ">>" -> vm.mirrorOf(l >> r);
    case ">>>" -> vm.mirrorOf(l >>> r);
    case "<" -> vm.mirrorOf(l < r);
    case ">" -> vm.mirrorOf(l > r);
    case "<=" -> vm.mirrorOf(l <= r);
    case ">=" -> vm.mirrorOf(l >= r);
    case "==" -> vm.mirrorOf(l == r);
    case "!=" -> vm.mirrorOf(l != r);
    default -> throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Unsupported operator: " + op);
    };
  }

  private Value interpretStringConcat(Value left, Value right, StackFrame frame, ThreadReference thread)
      throws Exception {
    String l = valueToString(left, thread);
    String r = valueToString(right, thread);
    return vm(frame).mirrorOf(l + r);
  }

  // ========== Unary Operations ==========

  private Value interpretUnaryOperation(Java.UnaryOperation unaryOp, StackFrame frame, ThreadReference thread)
      throws Exception {
    Value operand = interpretNode(unaryOp.operand, frame, thread);
    String op = unaryOp.operator;
    VirtualMachine vm = vm(frame);

    if ("!".equals(op)) {
      return vm.mirrorOf(!toBoolean(operand));
    }

    if (operand instanceof PrimitiveValue pv) {
      if ("-".equals(op)) {
        if (pv instanceof DoubleValue dv) {
          return vm.mirrorOf(-dv.value());
        }
        if (pv instanceof FloatValue fv) {
          return vm.mirrorOf(-fv.value());
        }
        if (pv instanceof LongValue lv) {
          return vm.mirrorOf(-lv.value());
        }
        return vm.mirrorOf(-toInt(pv));
      }
      if ("+".equals(op)) {
        return operand; // unary plus is a no-op
      }
      if ("~".equals(op)) {
        if (pv instanceof LongValue lv) {
          return vm.mirrorOf(~lv.value());
        }
        return vm.mirrorOf(~toInt(pv));
      }
    }

    throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
        "Unsupported unary operator '" + op + "' on " + describeType(operand));
  }

  // ========== Ternary ==========

  private Value interpretTernary(Java.ConditionalExpression ternary, StackFrame frame, ThreadReference thread)
      throws Exception {
    Value condition = interpretNode(ternary.lhs, frame, thread);
    if (toBoolean(condition)) {
      return interpretNode(ternary.mhs, frame, thread);
    } else {
      return interpretNode(ternary.rhs, frame, thread);
    }
  }

  // ========== Cast ==========

  private Value interpretCast(Java.Cast cast, StackFrame frame, ThreadReference thread) throws Exception {
    Value value = interpretNode(cast.value, frame, thread);
    Type targetType = resolveTypeFromCast(cast.targetType, frame);

    if (value == null) {
      return null; // null can be cast to any reference type
    }

    if (targetType == null) {
      // Can't resolve type — just return value (best effort)
      return value;
    }

    String targetName = targetType.name();

    // Primitive casts
    if (value instanceof PrimitiveValue pv && isPrimitiveTypeName(targetName)) {
      return coerceValue(pv, targetName, vm(frame));
    }

    // Reference type casts — JDI handles the check
    return value;
  }

  private Type resolveTypeFromCast(Java.Type castType, StackFrame frame) {
    try {
      String typeName = castType.toString();
      // Handle primitive types
      if (isPrimitiveTypeName(typeName)) {
        return null; // Primitives handled by name
      }
      List<ReferenceType> types = vm(frame).classesByName(typeName);
      return types.isEmpty() ? null : types.get(0);
    } catch (Exception e) {
      return null;
    }
  }

  // ========== Instanceof ==========

  private Value interpretInstanceof(Java.Instanceof instanceOf, StackFrame frame, ThreadReference thread)
      throws Exception {
    Value value = interpretNode(instanceOf.lhs, frame, thread);
    if (value == null) {
      return vm(frame).mirrorOf(false);
    }

    if (!(value instanceof ObjectReference objRef)) {
      return vm(frame).mirrorOf(false);
    }

    String targetTypeName = instanceOf.rhs.toString();
    List<ReferenceType> targetTypes = vm(frame).classesByName(targetTypeName);
    if (targetTypes.isEmpty()) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Cannot resolve type for instanceof: " + targetTypeName);
    }

    ReferenceType targetType = targetTypes.get(0);
    ReferenceType actualType = objRef.referenceType();

    // Walk the type hierarchy
    boolean result = isAssignableFrom(actualType, targetType);
    return vm(frame).mirrorOf(result);
  }

  private boolean isAssignableFrom(ReferenceType actual, ReferenceType target) {
    if (actual.equals(target)) {
      return true;
    }
    if (actual.name().equals(target.name())) {
      return true;
    }

    // Check interfaces
    if (actual instanceof ClassType ct) {
      for (var iface : ct.interfaces()) {
        if (isAssignableFrom(iface, target)) {
          return true;
        }
      }
      // Check superclass
      if (ct.superclass() != null) {
        return isAssignableFrom(ct.superclass(), target);
      }
    }

    if (actual instanceof com.sun.jdi.InterfaceType it) {
      for (var superIface : it.superinterfaces()) {
        if (isAssignableFrom(superIface, target)) {
          return true;
        }
      }
    }

    return false;
  }

  // ========== New Class Instance ==========

  private Value interpretNewClassInstance(Java.NewClassInstance newInst, StackFrame frame, ThreadReference thread)
      throws Exception {
    String className = newInst.type.toString();
    List<ReferenceType> types = vm(frame).classesByName(className);
    if (types.isEmpty()) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Cannot resolve class for new instance: " + className);
    }

    if (!(types.get(0) instanceof ClassType classType)) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "Cannot instantiate non-class type: " + className);
    }

    // Evaluate constructor arguments
    List<Value> argValues = new ArrayList<>();
    for (Java.Rvalue arg : newInst.arguments) {
      argValues.add(interpretNode(arg, frame, thread));
    }

    // Find constructor
    Method ctor = resolveMethod(classType, "<init>", argValues);
    if (ctor == null) {
      throw new DebuggerException(DebuggerErrorCode.EVALUATION_FAILED,
          "No matching constructor found for " + className + " with " + argValues.size() + " arguments");
    }

    List<Value> coerced = coerceArguments(argValues, ctor, vm(frame));
    return classType.newInstance(thread, ctor, coerced, ObjectReference.INVOKE_SINGLE_THREADED);
  }

  // ========== Value Formatting ==========

  /**
   * Formats a JDI Value for display.
   */
  String formatValue(Value value, ThreadReference thread) {
    if (value == null) {
      return "null";
    }

    if (value instanceof StringReference strRef) {
      return "\"" + strRef.value() + "\"";
    }

    if (value instanceof PrimitiveValue) {
      return value.toString();
    }

    if (value instanceof ArrayReference arr) {
      return describeArray(arr);
    }

    if (value instanceof ObjectReference objRef) {
      try {
        return valueToString(objRef, thread);
      } catch (Exception e) {
        return objRef.type().name() + "@" + objRef.uniqueID();
      }
    }

    return value.toString();
  }

  private String describeArray(ArrayReference arr) {
    int len = arr.length();
    if (len == 0) {
      return "[]";
    }
    if (len > 10) {
      return arr.type().name() + " (length=" + len + ")";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < len; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      Value elem = arr.getValue(i);
      sb.append(elem == null ? "null" : elem.toString());
    }
    sb.append("]");
    return sb.toString();
  }

  // ========== Helper Methods ==========

  private VirtualMachine vm(StackFrame frame) {
    return frame.virtualMachine();
  }

  private boolean isStringValue(Value value) {
    return value instanceof StringReference;
  }

  private boolean isFloatingPoint(PrimitiveValue value) {
    return value instanceof FloatValue || value instanceof DoubleValue;
  }

  private boolean toBoolean(Value value) {
    if (value instanceof BooleanValue bv) {
      return bv.value();
    }
    throw new DebuggerException(DebuggerErrorCode.EVALUATION_TYPE_MISMATCH,
        "Expected boolean, got " + describeType(value));
  }

  private int toInt(PrimitiveValue value) {
    if (value instanceof IntegerValue iv) {
      return iv.value();
    }
    if (value instanceof ShortValue sv) {
      return sv.value();
    }
    if (value instanceof ByteValue bv) {
      return bv.value();
    }
    if (value instanceof CharValue cv) {
      return cv.value();
    }
    if (value instanceof LongValue lv) {
      return (int) lv.value();
    }
    if (value instanceof FloatValue fv) {
      return (int) fv.value();
    }
    if (value instanceof DoubleValue dv) {
      return (int) dv.value();
    }
    if (value instanceof BooleanValue bv) {
      return bv.value() ? 1 : 0;
    }
    throw new DebuggerException(DebuggerErrorCode.EVALUATION_TYPE_MISMATCH,
        "Cannot convert " + describeType(value) + " to int");
  }

  private long toLong(PrimitiveValue value) {
    if (value instanceof LongValue lv) {
      return lv.value();
    }
    return toInt(value);
  }

  private double toDouble(PrimitiveValue value) {
    if (value instanceof DoubleValue dv) {
      return dv.value();
    }
    if (value instanceof FloatValue fv) {
      return fv.value();
    }
    return toLong(value);
  }

  private float toFloat(PrimitiveValue value) {
    if (value instanceof FloatValue fv) {
      return fv.value();
    }
    return (float) toLong(value);
  }

  private String valueToString(Value value, ThreadReference thread) throws Exception {
    if (value == null) {
      return "null";
    }
    if (value instanceof StringReference strRef) {
      return strRef.value();
    }
    if (value instanceof PrimitiveValue) {
      return value.toString();
    }
    if (value instanceof ObjectReference objRef) {
      // Invoke toString() on the object in the debuggee
      ReferenceType type = objRef.referenceType();
      List<Method> toStringMethods = type.methodsByName("toString", "()Ljava/lang/String;");
      if (!toStringMethods.isEmpty()) {
        Value result = objRef.invokeMethod(thread, toStringMethods.get(0), Collections.emptyList(),
            ObjectReference.INVOKE_SINGLE_THREADED);
        if (result instanceof StringReference sr) {
          return sr.value();
        }
      }
      return objRef.type().name() + "@" + objRef.uniqueID();
    }
    return value.toString();
  }

  private String describeType(Value value) {
    if (value == null) {
      return "null";
    }
    return value.type().name();
  }

  private boolean isClassObject(Value value) {
    if (!(value instanceof ObjectReference objRef)) {
      return false;
    }
    return objRef.referenceType().name().equals("java.lang.Class");
  }

  private ReferenceType getClassFromClassObject(ObjectReference classObj, StackFrame frame) {
    try {
      // Call Class.getName() to get the class name
      List<Method> methods = classObj.referenceType().methodsByName("getName", "()Ljava/lang/String;");
      if (!methods.isEmpty()) {
        Value nameVal = classObj.invokeMethod(frame.thread(), methods.get(0), Collections.emptyList(),
            ObjectReference.INVOKE_SINGLE_THREADED);
        if (nameVal instanceof StringReference sr) {
          List<ReferenceType> types = vm(frame).classesByName(sr.value());
          if (!types.isEmpty()) {
            return types.get(0);
          }
        }
      }
    } catch (Exception e) {
      logger.trace("Failed to resolve Class object: {}", e.getMessage());
    }
    return null;
  }
}
