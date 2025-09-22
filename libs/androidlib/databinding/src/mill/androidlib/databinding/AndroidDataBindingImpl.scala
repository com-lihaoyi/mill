package mill.androidlib.databinding

import android.databinding.tool.store.LayoutInfoInput
import android.databinding.tool.{BaseDataBinder, DataBindingBuilder, LayoutXmlProcessor}
import android.databinding.tool.writer.JavaFileWriter

import scala.jdk.CollectionConverters.*
import kotlin.jvm.functions.Function2

import java.io.File

class AndroidDataBindingImpl extends AndroidDataBindingWorker {

  override def processResources(args: ProcessResourcesArgs): Unit = {
    val processor = createXmlProcessor(args)
    val input = LayoutXmlProcessor.ResourceInput(
      false,
      os.Path(args.resInputDir).toIO,
      os.Path(args.resOutputDir).toIO
    )
    processor.processResources(input, args.enableViewBinding, args.enableDataBinding)

    processor.writeLayoutInfoFiles(os.Path(args.layoutInfoOutputDir).toIO)

  }

  def generateBindingSources(args: GenerateBindingSourcesArgs): Unit = {
    val args0 = new LayoutInfoInput.Args(
      Seq.empty[File].toList.asJava,
      Seq.empty[File].toList.asJava,
      new File(args.layoutInfoDir),
      args.dependencyClassInfoDirs.map(new File(_)).toList.asJava,
      new File(args.classInfoDir),
      new File(args.logFolder),
      args.applicationPackageName,
      false,
      null,
      args.useAndroidX,
      args.enableViewBinding,
      args.enableDataBinding
    )

    os.makeDir.all(os.Path(args.outputDir))
    val fileWriter = new DataBindingBuilder().createJavaFileWriter(os.Path(args.outputDir).toIO)

    new BaseDataBinder(
      new LayoutInfoInput(args0),
      null.asInstanceOf[Function2[String, String, String]]
    ).generateAll(fileWriter)

  }

  private def createXmlProcessor(args: ProcessResourcesArgs): LayoutXmlProcessor = {
    val fileWriter = ExecFileWriter(os.Path(args.layoutInfoOutputDir))
    LayoutXmlProcessor(
      args.applicationPackageName,
      fileWriter,
      OriginalFileLookup,
      args.useAndroidX
    )
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
