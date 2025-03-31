package mill.main.android.hilt

import com.android.build.api.instrumentation.{ClassContext, ClassData}
import dagger.hilt.android.plugin.transform.AndroidEntryPointClassVisitor
import mill._
import org.objectweb.asm._

object AndroidHiltTransformAsm {

  def main(args: Array[String]): Unit = {

    val scanDirectory = os.Path(args.head)

    val destination = os.Path(args.last)

    transformAsm(os.walk(scanDirectory).filter(_.ext == "class"), destination)

  }

  def transformAsm(classes: Seq[os.Path], destination: os.Path): Seq[os.Path] = {

    os.makeDir.all(destination)

    classes.map {
      path =>
        transform(path, destination / path.last)
    }

  }

  private def transform(`class`: os.Path, destination: os.Path): os.Path = {
    val originalClassBytes = os.read.bytes(`class`)
    val crCheck = new ClassReader(originalClassBytes)

    val dummyClassContext = new ClassContext {
      override def getCurrentClassData: ClassData = null

      override def loadClassData(s: String): ClassData = null
    }

    val scanner = new AnnotationScannerClassVisitor(Opcodes.ASM9)
    crCheck.accept(scanner, /* flags = */ 0)

    def isInstrumentable = scanner.isAndroidEntryPoint

    val cr = new ClassReader(originalClassBytes)

    if (isInstrumentable) {
      val cw = new ClassWriter(cr, /* flags = */ 0)

      val visitor = AndroidEntryPointClassVisitor(
        Opcodes.ASM9,
        cw,
        dummyClassContext
      )

      cr.accept(visitor, 0)

      // 6) Write the modified bytes
      val newBytes = cw.toByteArray
      os.write(destination, newBytes)
      destination
    } else {
      // If it's not annotated with @AndroidEntryPoint or @HiltAndroidApp, skip rewriting.
      os.copy(
        `class`,
        destination,
        createFolders = true
      ) // Just copy the file unmodified (optional).
      destination
    }
  }

  class AnnotationScannerClassVisitor(apiVersion: Int) extends ClassVisitor(apiVersion) {
    // We'll store whether we see a relevant annotation.
    var isAndroidEntryPoint: Boolean = false

    override def visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = {
      // The ASM "descriptor" for an annotation is typically "Lpackage/AnnotationClassName;"
      // For instance, "Ldagger/hilt/android/AndroidEntryPoint;"
      // We can check if it ends with "dagger/hilt/android/AndroidEntryPoint;"
      // or "dagger/hilt/android/HiltAndroidApp;"
      if (
        descriptor == "Ldagger/hilt/android/AndroidEntryPoint;" ||
        descriptor == "Ldagger/hilt/android/HiltAndroidApp;"
      ) {
        isAndroidEntryPoint = true
      }
      super.visitAnnotation(descriptor, visible)
    }
  }
}
