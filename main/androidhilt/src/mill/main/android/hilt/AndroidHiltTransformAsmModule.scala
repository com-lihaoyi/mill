package mill.main.android.hilt

import com.android.build.api.instrumentation.{ClassContext, ClassData}
import mill.*
import dagger.hilt.android.plugin.transform.AndroidEntryPointClassVisitor
import org.objectweb.asm.{ClassReader, ClassWriter, Opcodes}


object AndroidHiltTransformAsmModule {

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
    val cr = new ClassReader(originalClassBytes)
    val cw = new ClassWriter(cr, /* flags = */ 0)

    val dummyClassContext = new ClassContext {
      override def getCurrentClassData: ClassData = null

      override def loadClassData(s: String): ClassData = null
    }

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
  }
}
