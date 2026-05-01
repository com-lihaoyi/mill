package mill.javalib.zinc

import mill.api.daemon.Logger
import utest.*

object IncrementalAnnotationProcessingDetectionTests extends TestSuite {

  private val ProcessorServicePath =
    os.RelPath("META-INF/services/javax.annotation.processing.Processor")
  private val MetadataPath =
    os.RelPath("META-INF/gradle/incremental.annotation.processors")
  private val MarkerProperty = "mill.test.annotation.processor.marker"

  private case class ProcessorSpec(
      className: String,
      supportedAnnotationTypes: Seq[String],
      metadataKind: Option[String],
      constructorSideEffect: Boolean = false
  )

  private def writeSource(path: os.Path, content: String): Unit = {
    os.makeDir.all(path / os.up)
    os.write.over(path, content)
  }

  private def compileProcessor(root: os.Path, spec: ProcessorSpec): os.Path = {
    val classesDir = root / spec.className.split('.').last
    val pkg = spec.className.split('.').dropRight(1).mkString(".")
    val simpleName = spec.className.split('.').last
    val supported = spec.supportedAnnotationTypes.map(t => "\"" + t + "\"").mkString(", ")
    val sideEffect =
      if (spec.constructorSideEffect)
        s"""    public $simpleName() {
           |        String marker = System.getProperty("$MarkerProperty");
           |        if (marker != null) {
           |            try {
           |                java.nio.file.Files.writeString(java.nio.file.Path.of(marker), "$simpleName");
           |            } catch (java.io.IOException e) {
           |                throw new RuntimeException(e);
           |            }
           |        }
           |    }
           |""".stripMargin
      else ""

    writeSource(
      classesDir / os.RelPath(spec.className.replace('.', '/') + ".java"),
      s"""package $pkg;
         |
         |import java.util.Set;
         |import javax.annotation.processing.AbstractProcessor;
         |import javax.annotation.processing.RoundEnvironment;
         |import javax.annotation.processing.SupportedAnnotationTypes;
         |import javax.lang.model.SourceVersion;
         |import javax.lang.model.element.TypeElement;
         |
         |@SupportedAnnotationTypes({$supported})
         |public class $simpleName extends AbstractProcessor {
         |$sideEffect
         |    @Override
         |    public SourceVersion getSupportedSourceVersion() {
         |        return SourceVersion.latestSupported();
         |    }
         |
         |    @Override
         |    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
         |        return false;
         |    }
         |}
         |""".stripMargin
    )

    os.proc(
      "javac",
      "-d",
      classesDir.toString,
      (classesDir / os.RelPath(spec.className.replace('.', '/') + ".java")).toString
    ).call()

    writeSource(classesDir / ProcessorServicePath, spec.className + "\n")
    spec.metadataKind.foreach { kind =>
      writeSource(classesDir / MetadataPath, s"${spec.className},$kind\n")
    }
    classesDir
  }

  private def source(root: os.Path, rel: String, content: String): os.Path = {
    val path = root / os.RelPath(rel)
    writeSource(path, content)
    path
  }

  val tests: Tests = Tests {
    test("detectIgnoresInactiveServiceProviders") {
      val workDir = os.temp.dir()
      try {
        val relevantProcessor = compileProcessor(
          workDir,
          ProcessorSpec(
            className = "example.RelevantProcessor",
            supportedAnnotationTypes = Seq("example.Generate"),
            metadataKind = Some("isolating")
          )
        )
        val unrelatedProcessor = compileProcessor(
          workDir,
          ProcessorSpec(
            className = "example.UnrelatedProcessor",
            supportedAnnotationTypes = Seq("unrelated.Other"),
            metadataKind = None
          )
        )
        val src = source(
          workDir,
          "src/example/Annotated.java",
          """package example;
            |
            |@Generate
            |class Annotated {}
            |""".stripMargin
        )

        val mode = IncrementalAnnotationProcessing.detect(
          javacOptions = Seq(
            "-processorpath",
            s"${relevantProcessor}${java.io.File.pathSeparator}${unrelatedProcessor}"
          ),
          compileClasspath = Nil,
          sources = Seq(src),
          workDir = workDir,
          incrementalCompilation = true,
          log = Logger.Actions.noOp
        )

        assert(matchEnabled(mode))
      } finally os.remove.all(workDir)
    }

    test("detectDisablesForRelevantProcessorWithoutMetadata") {
      val workDir = os.temp.dir()
      try {
        val relevantProcessor = compileProcessor(
          workDir,
          ProcessorSpec(
            className = "example.RelevantProcessor",
            supportedAnnotationTypes = Seq("example.Generate"),
            metadataKind = None
          )
        )
        val src = source(
          workDir,
          "src/example/Annotated.java",
          """package example;
            |
            |@Generate
            |class Annotated {}
            |""".stripMargin
        )

        val mode = IncrementalAnnotationProcessing.detect(
          javacOptions = Seq("-processorpath", relevantProcessor.toString),
          compileClasspath = Nil,
          sources = Seq(src),
          workDir = workDir,
          incrementalCompilation = true,
          log = Logger.Actions.noOp
        )

        assert(mode == IncrementalAnnotationProcessing.Mode.Disabled)
      } finally os.remove.all(workDir)
    }

    test("detectIgnoresInactiveServiceProvidersOnCompileClasspathWithScalaSources") {
      val workDir = os.temp.dir()
      try {
        val unrelatedProcessor = compileProcessor(
          workDir,
          ProcessorSpec(
            className = "example.UnrelatedProcessor",
            supportedAnnotationTypes = Seq("unrelated.Other"),
            metadataKind = None
          )
        )
        val javaSrc = source(
          workDir,
          "src/example/Plain.java",
          """package example;
            |
            |class Plain {}
            |""".stripMargin
        )
        val scalaSrc = source(
          workDir,
          "src/example/PlainScala.scala",
          """package example
            |
            |class PlainScala
            |""".stripMargin
        )

        val mode = IncrementalAnnotationProcessing.detect(
          javacOptions = Nil,
          compileClasspath = Seq(unrelatedProcessor),
          sources = Seq(javaSrc, scalaSrc),
          workDir = workDir,
          incrementalCompilation = true,
          log = Logger.Actions.noOp
        )

        assert(mode == IncrementalAnnotationProcessing.Mode.None)
      } finally os.remove.all(workDir)
    }

    test("detectIgnoresServiceProvidersWithoutJavaSources") {
      val workDir = os.temp.dir()
      try {
        val processor = compileProcessor(
          workDir,
          ProcessorSpec(
            className = "example.Processor",
            supportedAnnotationTypes = Seq("*"),
            metadataKind = None
          )
        )
        val scalaSrc = source(
          workDir,
          "src/example/PlainScala.scala",
          """package example
            |
            |class PlainScala
            |""".stripMargin
        )

        val mode = IncrementalAnnotationProcessing.detect(
          javacOptions = Nil,
          compileClasspath = Seq(processor),
          sources = Seq(scalaSrc),
          workDir = workDir,
          incrementalCompilation = true,
          log = Logger.Actions.noOp
        )

        assert(mode == IncrementalAnnotationProcessing.Mode.None)
      } finally os.remove.all(workDir)
    }

    test("trackingProcessorWrappingIsDisabledWithoutTracker") {
      val workDir = os.temp.dir()
      try {
        val marker = workDir / "marker.txt"
        val sideEffectProcessor = compileProcessor(
          workDir,
          ProcessorSpec(
            className = "example.SideEffectProcessor",
            supportedAnnotationTypes = Seq("*"),
            metadataKind = Some("isolating"),
            constructorSideEffect = true
          )
        )

        System.setProperty(MarkerProperty, marker.toString)
        val disabled = IncrementalTrackingJavaCompiler.maybeLoadTrackingProcessors(
          javacOptions = Seq("-processor", "example.SideEffectProcessor"),
          processorPath = Seq(sideEffectProcessor),
          trackingEnabled = false
        )
        assert(disabled.isEmpty)
        assert(!os.exists(marker))

        val enabled = IncrementalTrackingJavaCompiler.maybeLoadTrackingProcessors(
          javacOptions = Seq("-processor", "example.SideEffectProcessor"),
          processorPath = Seq(sideEffectProcessor),
          trackingEnabled = true
        )
        try assert(enabled.nonEmpty, os.exists(marker))
        finally enabled.foreach(_.close())
      } finally {
        System.clearProperty(MarkerProperty)
        os.remove.all(workDir)
      }
    }
  }

  private def matchEnabled(mode: IncrementalAnnotationProcessing.Mode): Boolean =
    mode match {
      case IncrementalAnnotationProcessing.Mode.Enabled(_) => true
      case _ => false
    }
}
