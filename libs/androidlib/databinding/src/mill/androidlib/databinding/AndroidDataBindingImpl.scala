package mill.androidlib.databinding


import android.databinding.tool.LayoutXmlProcessor
import android.databinding.tool.writer.JavaFileWriter

import java.io.File


class AndroidDataBindingImpl extends AndroidDataBinding {

  override def processResources(args: ProcessResourcesArgs): Unit = {
    val processor = createXmlProcessor(args)
    val input = LayoutXmlProcessor.ResourceInput(
      false,
      os.Path(args.resInputDir).toIO,
      os.Path(args.resOutputDir).toIO
    )
    processor.processResources(input, false, true)

    processor.writeLayoutInfoFiles(os.Path(args.layoutInfoOutputDir).toIO)

  }

  def generateBaseClasses(args: GenerateBaseClassesArgs): Unit = {

  }

  private def createXmlProcessor(args: ProcessResourcesArgs): LayoutXmlProcessor  = {
    val fileWriter = ExecFileWriter(os.Path(args.layoutInfoOutputDir))
    LayoutXmlProcessor(args.applicationPackageName, fileWriter, OriginalFileLookup, args.useAndroidX)
  }

  private object OriginalFileLookup extends LayoutXmlProcessor.OriginalFileLookup {
    override def getOriginalFileFor(file: File): File = file
  }

  private class ExecFileWriter(base: os.Path) extends JavaFileWriter {
    override def writeToFile(canonicalName: String, contents: String): Unit = {
      val f = toFile(canonicalName)
      writeToFile(f, contents)
    }

    private def toFile(canonicalName: String): File = {
      val asPath = canonicalName.replace('.', '/')
      os.Path(s"base/${asPath}.java").toIO
    }

    override def deleteFile(canonicalName: String): Unit = {
      val file = os.Path(canonicalName)
      if (os.exists(file)) {
        os.remove(file)
      }
    }
  }
}
