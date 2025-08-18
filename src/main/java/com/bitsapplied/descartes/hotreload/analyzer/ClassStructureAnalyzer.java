package com.bitsapplied.descartes.hotreload.analyzer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Analyzes Java class bytecode structure using ASM to determine if classes can
 * be safely redefined at runtime.
 * 
 * @author Descartes MCP
 */
public class ClassStructureAnalyzer {

  /**
   * Analyze the structure of a class from its bytecode.
   * 
   * @param bytecode Class bytecode
   * @return ClassStructure containing analyzed information
   */
  public ClassStructure analyzeStructure(byte[] bytecode) {
    if (bytecode == null || bytecode.length == 0) {
      throw new IllegalArgumentException("Bytecode cannot be null or empty");
    }

    ClassReader reader = new ClassReader(bytecode);
    ClassStructureVisitor visitor = new ClassStructureVisitor();
    reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    return visitor.getStructure();
  }

  /**
   * Check if two class structures are compatible for redefinition.
   * 
   * @param current      Current class structure
   * @param newStructure New class structure
   * @return true if compatible for redefinition
   */
  public boolean areCompatibleForRedefinition(ClassStructure current, ClassStructure newStructure) {
    return current.getIncompatibilities(newStructure).isEmpty();
  }

  /**
   * ASM visitor that extracts class structure information.
   */
  private static class ClassStructureVisitor extends ClassVisitor {

    private final ClassStructure structure = new ClassStructure();

    public ClassStructureVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
      structure.setClassName(name);
      structure.setSuperClassName(superName);
      structure.setAccess(access);
      structure.setSignature(signature);

      if (interfaces != null) {
        for (String iface : interfaces) {
          structure.addInterface(iface);
        }
      }
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
      FieldSignature field = new FieldSignature(access, name, descriptor, signature);
      structure.addField(field);
      return null; // We don't need to visit field annotations
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
        String[] exceptions) {
      MethodSignature method = new MethodSignature(access, name, descriptor, signature, exceptions);
      structure.addMethod(method);
      return null; // We don't need to visit method code
    }

    public ClassStructure getStructure() {
      return structure;
    }
  }
}