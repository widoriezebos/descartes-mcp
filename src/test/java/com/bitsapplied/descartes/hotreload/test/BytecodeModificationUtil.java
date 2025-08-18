package com.bitsapplied.descartes.hotreload.test;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Utility class for modifying bytecode during hot reload testing. Uses ASM to
 * create modified versions of test classes.
 */
public class BytecodeModificationUtil {

  /**
   * Modify the ReloadableTestClass to return a different version number.
   */
  public static byte[] modifyReloadableTestClassVersion(byte[] originalBytecode, final int newVersion) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
          String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Modify the getVersion method
        if ("getVersion".equals(name) && "()I".equals(descriptor)) {
          return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
              // Return the new version number directly
              super.visitCode();
              visitIntInsn(Opcodes.BIPUSH, newVersion);
              visitInsn(Opcodes.IRETURN);
              visitMaxs(1, 0);
              visitEnd();
            }
          };
        }

        // Modify the calculate method to do multiplication instead of addition
        if ("calculate".equals(name) && "(II)I".equals(descriptor)) {
          return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
              // Change from addition to multiplication
              super.visitCode();
              visitVarInsn(Opcodes.ILOAD, 1);
              visitVarInsn(Opcodes.ILOAD, 2);
              visitInsn(Opcodes.IMUL); // Changed from IADD to IMUL
              visitInsn(Opcodes.IRETURN);
              visitMaxs(2, 3);
              visitEnd();
            }
          };
        }

        return mv;
      }
    };

    reader.accept(visitor, 0);
    return writer.toByteArray();
  }

  /**
   * Create bytecode with an added field (incompatible change).
   */
  public static byte[] addFieldToBytecode(byte[] originalBytecode) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override
      public void visitEnd() {
        // Add a new field
        FieldVisitor fv = cv.visitField(Opcodes.ACC_PRIVATE, "newField", "Ljava/lang/String;", null, "new field value");
        fv.visitEnd();
        super.visitEnd();
      }
    };

    reader.accept(visitor, 0);
    return writer.toByteArray();
  }

  /**
   * Create bytecode with changed method signature (incompatible change).
   */
  public static byte[] changeMethodSignature(byte[] originalBytecode) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
          String[] exceptions) {
        // Change method signature from (String) to (String, int)
        if ("methodWithSignature".equals(name) && "(Ljava/lang/String;)Ljava/lang/String;".equals(descriptor)) {
          // Skip the original method
          return null;
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
      }

      @Override
      public void visitEnd() {
        // Add the method with new signature
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "methodWithSignature",
            "(Ljava/lang/String;I)Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("Modified: ");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
            "(Ljava/lang/String;)Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();

        super.visitEnd();
      }
    };

    reader.accept(visitor, 0);
    return writer.toByteArray();
  }

  /**
   * Create bytecode with changed superclass (incompatible change).
   */
  public static byte[] changeSuperclass(byte[] originalBytecode) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override
      public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        // Change superclass from Object to Thread (incompatible)
        super.visit(version, access, name, signature, "java/lang/Thread", interfaces);
      }
    };

    reader.accept(visitor, 0);
    return writer.toByteArray();
  }

  /**
   * Create bytecode with only method body changes (compatible).
   */
  public static byte[] modifyMethodBody(byte[] originalBytecode, final String newMessage) {
    ClassReader reader = new ClassReader(originalBytecode);
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
          String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // Modify the getMessage method
        if ("getMessage".equals(name) && "()Ljava/lang/String;".equals(descriptor)) {
          return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
              super.visitCode();
              visitLdcInsn(newMessage);
              visitInsn(Opcodes.ARETURN);
              visitMaxs(1, 1);
              visitEnd();
            }
          };
        }

        return mv;
      }
    };

    reader.accept(visitor, 0);
    return writer.toByteArray();
  }
}