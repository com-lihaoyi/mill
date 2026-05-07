package mill.androidlib.hilt

import com.android.build.api.instrumentation.{ClassContext, ClassData}
import dagger.hilt.android.plugin.transform.AndroidEntryPointClassVisitor
import org.objectweb.asm.*

/**
 * A standalone executable utility to transform compiled classes with Hilt dependency injection context.
 *
 * Example usage:
 * `java -classpath <classpath> mill.kotlinlib.android.hilt.AndroidHiltTransformAsm <input-directory> <output-directory>`
 * where the input directory is the directory containing the compiled classes and the output-directory
 * an already existing directory to place the transformed classes.
 *
 * The classes are only transformed if they contain the @AndroidEntryPoint or @HiltAndroidApp annotations,
 * otherwise they are copied as is. This code is an adaptation of the gradle hilt plugin transformASM task that can be found
 * in [[https://github.com/google/dagger/blob/b3d3443e3581b8530cd85929614a1765cd37b12c/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/transform/AndroidEntryPointClassVisitor.kt#L122]]
 */
@mill.api.experimental
object AndroidHiltTransformAsm {

  def main(args: Array[String]): Unit = {

    val scanDirectory = os.Path(args.head)

    val destination = os.Path(args.last)

    val allCompiledFiles = os.walk(scanDirectory).filter(os.isFile)

    transformAsm(scanDirectory, allCompiledFiles.filter(_.ext == "class"), destination)

    allCompiledFiles.filterNot(_.ext == "class").foreach { file =>
      val destinationFile = destination / file.relativeTo(scanDirectory)
      os.copy(file, destinationFile, createFolders = true)
    }

  }

  def transformAsm(
      baseDir: os.Path,
      classes: Seq[os.Path],
      destinationDir: os.Path
  ): Seq[os.Path] = {

    os.makeDir.all(destinationDir)
    classes.map {
      path =>
        val destination = destinationDir / path.relativeTo(baseDir)
        transform(path, destination)
    }
  }

  private def transform(`class`: os.Path, destination: os.Path): os.Path = {
    val originalClassBytes = os.read.bytes(`class`)
    val crCheck = ClassReader(originalClassBytes)

    val dummyClassContext = new ClassContext {
      override def getCurrentClassData: ClassData = null

      override def loadClassData(s: String): ClassData = null
    }

    val scanner = AnnotationScannerClassVisitor(Opcodes.ASM9)
    crCheck.accept(scanner, /* flags = */ 0)

    def isInstrumentable = scanner.isAndroidEntryPoint

    val cr = ClassReader(originalClassBytes)

    if (isInstrumentable) {
      val cw = ClassWriter(cr, /* flags = */ 0)

      val visitor = AndroidEntryPointClassVisitor(
        Opcodes.ASM9,
        cw,
        dummyClassContext
      )

      cr.accept(visitor, 0)

      // 6) Write the modified bytes
      val newBytes = cw.toByteArray
      os.write(destination, newBytes, createFolders = true)
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
