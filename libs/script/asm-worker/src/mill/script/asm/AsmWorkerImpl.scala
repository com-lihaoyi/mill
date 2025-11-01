package mill.script.asm

import org.objectweb.asm

class AsmWorkerImpl {

  def generateSyntheticClasses(classesDir: java.nio.file.Path): Unit = {
    val mainMethods = findMainArgsMethods(os.Path(classesDir))

    mainMethods.foreach { methodName => // Generate synthetic classes for each method
      generateSyntheticMainClass(os.Path(classesDir), methodName)
    }
  }

  private def findMainArgsMethods(classesDir: os.Path): Seq[String] = {
    val mainMethods = collection.mutable.ArrayBuffer[String]()

    // Look for _MillScriptMain$ class which contains the mainargs.Parser code
    val millScriptMainClass = classesDir / "_MillScriptMain$.class"

    if (os.exists(millScriptMainClass)) {
      val reader = new asm.ClassReader(os.read.bytes(millScriptMainClass))

      val visitor = new asm.ClassVisitor(asm.Opcodes.ASM9) {
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): asm.MethodVisitor = {
          new asm.MethodVisitor(asm.Opcodes.ASM9) {
            private val stringsSinceLastCreate = collection.mutable.ArrayBuffer[String]()

            override def visitLdcInsn(value: Any): Unit = {
              value match {
                case s: String => stringsSinceLastCreate += s
                case _ =>
              }
              super.visitLdcInsn(value)
            }

            override def visitMethodInsn(
                opcode: Int,
                owner: String,
                methodName: String,
                descriptor: String,
                isInterface: Boolean
            ): Unit = {
              // Look for MainData.create calls which include the method name as first parameter
              if (owner.contains("MainData") && methodName == "create") {
                // The first string constant before MainData.create is the method name
                if (stringsSinceLastCreate.nonEmpty) {
                  val potentialMethodName = stringsSinceLastCreate.head
                  mainMethods += potentialMethodName
                  stringsSinceLastCreate.clear()
                }
              }

              super.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface)
            }
          }
        }
      }

      reader.accept(visitor, 0)
    }

    mainMethods.toSeq.distinct
  }

  private def generateSyntheticMainClass(classesDir: os.Path, methodName: String): Unit = {
    val templateBytes = os.read.bytes(os.resource(getClass.getClassLoader) / "mill/script/asm/TemplateMainClass.class")
    val reader = new asm.ClassReader(templateBytes)
    val writer = new asm.ClassWriter(reader, 0)

    val visitor = new asm.ClassVisitor(asm.Opcodes.ASM9, writer) {
      override def visit(
          version: Int,
          access: Int,
          name: String,
          signature: String,
          superName: String,
          interfaces: Array[String]
      ): Unit = {
        super.visit(version, access, methodName, signature, superName, interfaces)
      }

      override def visitMethod(
          access: Int,
          name: String,
          descriptor: String,
          signature: String,
          exceptions: Array[String]
      ): asm.MethodVisitor = {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)

        new asm.MethodVisitor(asm.Opcodes.ASM9, mv) {
          override def visitLdcInsn(value: Any): Unit = {
            // Replace "TEMPLATE_METHOD_NAME" with actual method name
            if (value == "TEMPLATE_METHOD_NAME") {
              super.visitLdcInsn(methodName)
            } else {
              super.visitLdcInsn(value)
            }
          }

          override def visitMethodInsn(
              opcode: Int,
              owner: String,
              name: String,
              descriptor: String,
              isInterface: Boolean
          ): Unit = {
            // Replace TemplateMainClass.main call with _MillScriptMain$.main
            if (owner == "mill/script/asm/TemplateMainClass" && name == "main") {
              super.visitMethodInsn(opcode, "_MillScriptMain", name, descriptor, isInterface)
            } else {
              super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            }
          }
        }
      }
    }

    reader.accept(visitor, 0)

    // Write the modified class file
    val classBytes = writer.toByteArray
    os.write(classesDir / s"$methodName.class", classBytes)
  }
}
