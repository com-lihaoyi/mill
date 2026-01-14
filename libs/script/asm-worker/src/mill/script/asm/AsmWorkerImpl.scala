package mill.script.asm

import org.objectweb.asm

object AsmWorkerImpl {

  def generateSyntheticClasses(classesDir: java.nio.file.Path, mainMethods: Array[String]): Unit = {
    // Find the MillScriptMain_ class name to forward to
    val targetClassName = os.list(os.Path(classesDir))
      .map(_.last)
      .collectFirst { case s"MillScriptMain_${s}$$.class" => s }
      .getOrElse("MillScriptMain_")

    mainMethods.foreach { methodName =>
      generateSyntheticMainClass(
        os.Path(classesDir),
        methodName,
        mainMethods.size > 1,
        targetClassName
      )
    }
  }

  def findMainArgsMethods(classesDir: java.nio.file.Path): Array[String] = {
    val mainMethods = collection.mutable.ArrayBuffer[String]()

    // Look for MillScriptMain_ classes which contain the mainargs.Parser code
    // The class name is prefixed with the script name (e.g., MillScriptMain_Multi)
    val millScriptMainClasses = os.list(os.Path(classesDir))
      .filter(p => p.last.startsWith("MillScriptMain_") && p.last.endsWith("$.class"))

    for (classFile <- millScriptMainClasses) {
      val reader = new asm.ClassReader(os.read.bytes(classFile))

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

    mainMethods.toArray.distinct
  }

  def generateSyntheticMainClass(
      classesDir: os.Path,
      methodName: String,
      multiMain: Boolean,
      targetClassName: String
  ): Unit = {
    val templateClassName = if (multiMain) "TemplateMultiMainClass" else "TemplateSingleMainClass"
    val templateBytes = os.read.bytes(
      os.resource(getClass().getClassLoader()) / os.SubPath(
        s"mill/script/asm/$templateClassName.class"
      )
    )
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
            if (value == "TEMPLATE_METHOD_NAME") super.visitLdcInsn(methodName)
            else super.visitLdcInsn(value)
          }

          override def visitMethodInsn(
              opcode: Int,
              owner: String,
              name: String,
              descriptor: String,
              isInterface: Boolean
          ): Unit = {
            // Replace TemplateMainClass.main call with the actual MillScriptMain_.main
            if (owner == s"mill/script/asm/$templateClassName" && name == "main") {
              super.visitMethodInsn(opcode, targetClassName, name, descriptor, isInterface)
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
    os.write.over(classesDir / s"$methodName.class", classBytes)
  }
}
