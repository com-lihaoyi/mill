package mill.androidlib.databinding

import android.databinding.tool.store.LayoutInfoInput
import android.databinding.tool.writer.JavaFileWriter
import android.databinding.tool.{BaseDataBinder, DataBindingBuilder, LayoutXmlProcessor}

import java.io.File
import java.util.Collections
import scala.jdk.CollectionConverters.*

/**
 * DataBinding implementation
 * https://android.googlesource.com/platform/frameworks/data-binding/+/85dd11e6e0da7a35ca0c154beaf02b7f7217bd2f/exec/src/main/java/android/databinding/AndroidDataBinding.kt
 */
class AndroidDataBindingImpl extends AndroidDataBindingWorker {

  /**
   * Process Android resources to generate layout info files
   */
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

  /**
   * Generate binding sources from layout info files
   */
  def generateBindingSources(args: GenerateBindingSourcesArgs): Unit = {
    val layoutInfoInputArgs = new LayoutInfoInput.Args(
      /* outOfDate */ Collections.emptyList(),
      /* removed */ Collections.emptyList(),
      /* infoFolder */ new File(args.layoutInfoDir),
      /* dependencyClassesFolders */ args.dependencyClassInfoDirs.map(new File(_)).toList.asJava,
      /* artifactFolder */ new File(args.classInfoDir),
      /* logFolder */ new File(args.logFolder),
      /* packageName */ args.applicationPackageName,
      /* incremental */ false,
      /* v1ArtifactsFolder */ null,
      /* useAndroidX */ args.useAndroidX,
      /* enableViewBinding */ args.enableViewBinding,
      /* enableDataBinding */ args.enableDataBinding
    )

    os.makeDir.all(os.Path(args.outputDir))
    val fileWriter = new DataBindingBuilder().createJavaFileWriter(os.Path(args.outputDir).toIO)

    val getRPackage: kotlin.jvm.functions.Function2[String, String, String] = null

    new BaseDataBinder(
      new LayoutInfoInput(layoutInfoInputArgs),
      getRPackage
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
