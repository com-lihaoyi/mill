package mill.meta

// originally based on https://github.com/VirtusLab/scala-cli/blob/67db91cfbaa74de806fd1d5ed00096affa0125c0/modules/build/src/main/scala/scala/build/postprocessing/AsmPositionUpdater.scala

import org.objectweb.asm
import java.io.InputStream

object AsmPositionUpdater {

  private class LineNumberTableMethodVisitor(
                                              lineNumberOffset: Int,
                                              delegate: asm.MethodVisitor
                                            ) extends asm.MethodVisitor(asm.Opcodes.ASM9, delegate) {
    override def visitLineNumber(line: Int, start: asm.Label): Unit =
      super.visitLineNumber(math.max(0, line + lineNumberOffset), start)
  }

  private class LineNumberTableClassVisitor(
                                             lineNumberOffset: Int,
                                             cw: asm.ClassWriter
                                           ) extends asm.ClassVisitor(asm.Opcodes.ASM9, cw) {

    override def visitMethod(
                              access: Int,
                              name: String,
                              descriptor: String,
                              signature: String,
                              exceptions: Array[String]
                            ): asm.MethodVisitor = {
      val main = super.visitMethod(access, name, descriptor, signature, exceptions)
      new LineNumberTableMethodVisitor(lineNumberOffset, main)

    }
  }

  def postProcess(lineNumberOffset: Int, clsInputStream: InputStream): Array[Byte] = {
    val reader = new asm.ClassReader(clsInputStream)
    val writer = new asm.ClassWriter(reader, 0)
    val checker = new LineNumberTableClassVisitor(lineNumberOffset, writer)
    reader.accept(checker, 0)
    writer.toByteArray
  }
}
