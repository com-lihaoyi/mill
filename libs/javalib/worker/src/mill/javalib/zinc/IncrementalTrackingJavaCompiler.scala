package mill.javalib.zinc
import sbt.internal.inc.javac.{DirectToJarFileManager, SameFileFixFileManager}
import sbt.util.{Level, Logger}
import xsbti.{Reporter, VirtualFile}
import xsbti.compile.{IncToolOptions, JavaCompiler as XJavaCompiler, Output}

import java.io.{OutputStream, PrintWriter, Writer}
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}
import javax.annotation.processing.{Completion, Filer, Messager, Processor, ProcessingEnvironment}
import javax.lang.model.SourceVersion
import javax.lang.model.element.{AnnotationMirror, Element, ExecutableElement, TypeElement}
import javax.lang.model.util.{Elements, Types}
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject.Kind
import javax.tools.{
  FileObject,
  ForwardingFileObject,
  ForwardingJavaFileManager,
  ForwardingJavaFileObject,
  JavaFileManager,
  JavaFileObject,
  StandardJavaFileManager
}
import scala.jdk.CollectionConverters.*
import xsbti.PathBasedFile

private[mill] object IncrementalTrackingJavaCompiler {
  private[mill] final case class LoadedProcessors(
      loader: java.net.URLClassLoader,
      processors: Seq[Processor]
  ) extends AutoCloseable {
    override def close(): Unit = loader.close()
  }

  def local: Option[XJavaCompiler] =
    Option(
      javax.tools.ToolProvider.getSystemJavaCompiler
    ).map(new IncrementalTrackingJavaCompiler(_))

  private[mill] def maybeLoadTrackingProcessors(
      javacOptions: Seq[String],
      processorPath: Seq[os.Path],
      trackingEnabled: Boolean
  ): Option[LoadedProcessors] = {
    if (!trackingEnabled) None
    else {
      val names = IncrementalAnnotationProcessing.explicitProcessors(javacOptions).getOrElse {
        processorPath.iterator.flatMap(
          IncrementalAnnotationProcessing.readProcessorServiceFile
        ).toSeq
      }.distinct

      if (names.isEmpty) None
      else {
        val urls = processorPath.iterator.map(_.toIO.toURI.toURL).toArray
        val loader = new java.net.URLClassLoader(urls, getClass.getClassLoader)
        val processors = names.map { name =>
          val delegate =
            loader.loadClass(name).getDeclaredConstructor().newInstance().asInstanceOf[Processor]
          if (needsRawProcessingEnvironment(delegate)) delegate
          else new TrackingProcessor(delegate)
        }
        Some(LoadedProcessors(loader, processors))
      }
    }
  }

  // Lombok's processor stack expects the original javac ProcessingEnvironment rather than our
  // TrackingProcessingEnvironment wrapper. See:
  // - https://github.com/projectlombok/lombok/blob/v1.18.38/src/core/lombok/launch/AnnotationProcessor.java
  // - https://github.com/projectlombok/lombok/blob/v1.18.38/src/core/lombok/javac/apt/Processor.java
  private def needsRawProcessingEnvironment(processor: Processor): Boolean =
    processor.getClass.getName.startsWith("lombok.")
}

private[mill] final class IncrementalTrackingJavaCompiler(compiler: javax.tools.JavaCompiler)
    extends XJavaCompiler {
  override def supportsDirectToJar: Boolean = true

  override def run(
      sources: Array[VirtualFile],
      options: Array[String],
      output: Output,
      incToolOptions: IncToolOptions,
      reporter: Reporter,
      log0: xsbti.Logger
  ): Boolean = {
    val log: Logger = log0
    val logger = new sbt.internal.util.LoggerWriter(log)
    val logWriter = new PrintWriter(logger)
    val diagnostics = new sbt.internal.inc.javac.DiagnosticsReporter(reporter)

    val (invalidOptions, cleanedOptions) = options.partition(_.startsWith("-J"))
    if (invalidOptions.nonEmpty) {
      log.warn("Javac is running in 'local' mode. These flags have been removed:")
      log.warn(invalidOptions.mkString("\t", ", ", ""))
    }

    def standardFileManager = compiler.getStandardFileManager(diagnostics, null, null)

    val (fileManager, javacOptions) = sbt.internal.inc.JarUtils.getOutputJar(output) match {
      case Some(outputJar) =>
        (new DirectToJarFileManager(outputJar, standardFileManager), cleanedOptions.toSeq)
      case None =>
        Option(output.getSingleOutputAsPath.orElse(null: Path)).foreach(Files.createDirectories(_))
        val baseFileManager =
          if (cleanedOptions.contains("-XDuseOptimizedZip=false"))
            fileManagerWithoutOptimizedZips(diagnostics)
          else standardFileManager
        val outputOption = sbt.internal.inc.CompilerArguments.outputOption(output)
        (baseFileManager, outputOption ++ cleanedOptions)
    }

    val jfiles = sources.toList.map(TrackingVirtualJavaFileObject(_))
    val customizedFileManager = {
      val maybeClassFileManager = incToolOptions.classFileManager()
      if (incToolOptions.useCustomizedFileManager && maybeClassFileManager.isPresent)
        new TrackingFileManager(
          fileManager,
          Some(maybeClassFileManager.get),
          IncrementalAnnotationProcessing.currentTracker
        )
      else
        new TrackingFileManager(
          new SameFileFixFileManager(fileManager),
          None,
          IncrementalAnnotationProcessing.currentTracker
        )
    }

    val task =
      compiler.getTask(
        logWriter,
        customizedFileManager,
        diagnostics,
        javacOptions.toList.asJava,
        null,
        jfiles.asJava
      )

    val processorPath = processorPathFromOptions(cleanedOptions.toSeq)
    val loadedProcessors = IncrementalTrackingJavaCompiler.maybeLoadTrackingProcessors(
      cleanedOptions.toSeq,
      processorPath,
      IncrementalAnnotationProcessing.currentTracker.nonEmpty
    )
    loadedProcessors.foreach { loaded =>
      if (loaded.processors.nonEmpty) task.setProcessors(loaded.processors.asJava)
    }

    var compileSuccess = false
    try {
      val success = task.call()
      compileSuccess = success && !diagnostics.hasErrors
    } finally {
      loadedProcessors.foreach(_.close())
      customizedFileManager.close()
      logger.flushLines(if (compileSuccess) Level.Warn else Level.Error)
    }
    compileSuccess
  }

  private def processorPathFromOptions(javacOptions: Seq[String]): Seq[os.Path] = {
    val classpath = IncrementalAnnotationProcessing
      .parsePathOption(javacOptions, "-classpath", "-classpath")
      .orElse(IncrementalAnnotationProcessing.parsePathOption(javacOptions, "-cp", "-cp"))
      .getOrElse(Nil)
      .map(os.Path(_, os.pwd))

    IncrementalAnnotationProcessing
      .parsePathOption(javacOptions, "-processorpath", "--processor-path")
      .map(_.map(os.Path(_, os.pwd)))
      .getOrElse(classpath)
  }

  private def fileManagerWithoutOptimizedZips(
      diagnostics: sbt.internal.inc.javac.DiagnosticsReporter
  ): StandardJavaFileManager = {
    val classLoader = compiler.getClass.getClassLoader
    val contextClass = Class.forName("com.sun.tools.javac.util.Context", true, classLoader)
    val optionsClass = Class.forName("com.sun.tools.javac.util.Options", true, classLoader)
    val javacFileManagerClass =
      Class.forName("com.sun.tools.javac.file.JavacFileManager", true, classLoader)

    val `Options.instance` = optionsClass.getMethod("instance", contextClass)
    val `context.put` = contextClass.getMethod("put", classOf[Class[?]], classOf[Object])
    val `options.put` = optionsClass.getMethod("put", classOf[String], classOf[String])
    val `new JavacFileManager` =
      javacFileManagerClass.getConstructor(contextClass, classOf[Boolean], classOf[Charset])

    val context = contextClass.getDeclaredConstructor().newInstance().asInstanceOf[AnyRef]
    `context.put`.invoke(context, classOf[java.util.Locale], null)
    `context.put`.invoke(context, classOf[DiagnosticListener[?]], diagnostics)
    val options = `Options.instance`.invoke(null, context)
    `options.put`.invoke(options, "useOptimizedZip", "false")

    `new JavacFileManager`
      .newInstance(context, Boolean.box(true), null)
      .asInstanceOf[StandardJavaFileManager]
  }
}

private sealed trait TrackingOutputObject {
  def delegate: FileObject
  def path: Option[Path]
}

private final case class TrackingVirtualJavaFileObject(underlying: VirtualFile)
    extends javax.tools.SimpleJavaFileObject(
      TrackingVirtualJavaFileObject.uriFor(underlying),
      Kind.SOURCE
    ) {
  override def openInputStream = underlying.input
  override def getName: String = underlying.name
  override def toString: String = underlying.id
  override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = {
    val in = underlying.input
    try sbt.io.IO.readStream(in)
    finally in.close()
  }
}

private object TrackingVirtualJavaFileObject {
  def uriFor(underlying: VirtualFile): URI =
    underlying match {
      case pathBased: PathBasedFile => pathBased.toPath.toUri
      case _ => new URI("vf", "tmp", s"/${underlying.id}", null)
    }
}

private final class TrackingFileManager(
    underlying: JavaFileManager,
    classFileManager: Option[xsbti.compile.ClassFileManager],
    tracker: Option[IncrementalAnnotationProcessing.CompileTracker]
) extends ForwardingJavaFileManager[JavaFileManager](underlying) {
  override def getJavaFileForOutput(
      location: JavaFileManager.Location,
      className: String,
      kind: Kind,
      sibling: FileObject
  ): JavaFileObject = {
    val output = super.getJavaFileForOutput(location, className, kind, sibling)
    if (kind == Kind.CLASS) new TrackingJavaFileObject(output, sibling, classFileManager, tracker)
    else output
  }

  override def isSameFile(a: FileObject, b: FileObject): Boolean =
    TrackingFileManager.unwrap(a) == TrackingFileManager.unwrap(b)
}

private object TrackingFileManager {
  def unwrap(fileObject: FileObject): AnyRef =
    fileObject match {
      case tracked: TrackingOutputObject => tracked.delegate
      case other => other
    }
}

private final class TrackingJavaFileObject(
    val delegate: JavaFileObject,
    sibling: FileObject,
    classFileManager: Option[xsbti.compile.ClassFileManager],
    tracker: Option[IncrementalAnnotationProcessing.CompileTracker]
) extends ForwardingJavaFileObject[JavaFileObject](delegate)
    with TrackingOutputObject {
  val path: Option[Path] = IncrementalAnnotationProcessing.fileObjectPath(delegate)
  private val siblingPath = Option(sibling).flatMap(IncrementalAnnotationProcessing.fileObjectPath)

  override def openWriter(): Writer = {
    onOpen(super.openWriter())
  }

  override def openOutputStream(): OutputStream = {
    onOpen(super.openOutputStream())
  }

  private def onOpen[T](result: => T): T = {
    classFileManager.foreach(
      _.generated(
        Array[VirtualFile](sbt.internal.inc.PlainVirtualFile(path.getOrElse(Paths.get(toUri))))
      )
    )
    tracker.foreach(_.recordSiblingGenerated(path, siblingPath))
    result
  }
}

private final class TrackingFilerJavaFileObject(
    val delegate: JavaFileObject,
    tracker: Option[IncrementalAnnotationProcessing.CompileTracker],
    owners: Set[os.Path]
) extends ForwardingJavaFileObject[JavaFileObject](delegate)
    with TrackingOutputObject {
  val path: Option[Path] = IncrementalAnnotationProcessing.fileObjectPath(delegate)

  override def openWriter(): Writer = {
    onOpen(super.openWriter())
  }

  override def openOutputStream(): OutputStream = {
    onOpen(super.openOutputStream())
  }

  private def onOpen[T](result: => T): T = {
    tracker.foreach(_.recordOwnedGenerated(path, owners))
    result
  }
}

private final class TrackingFilerFileObject(
    val delegate: FileObject,
    tracker: Option[IncrementalAnnotationProcessing.CompileTracker],
    owners: Set[os.Path]
) extends ForwardingFileObject[FileObject](delegate)
    with TrackingOutputObject {
  val path: Option[Path] = IncrementalAnnotationProcessing.fileObjectPath(delegate)

  override def openWriter(): Writer = {
    onOpen(super.openWriter())
  }

  override def openOutputStream(): OutputStream = {
    onOpen(super.openOutputStream())
  }

  private def onOpen[T](result: => T): T = {
    tracker.foreach(_.recordOwnedGenerated(path, owners))
    result
  }
}

private final class TrackingProcessor(delegate: Processor) extends Processor {
  override def getSupportedOptions(): java.util.Set[String] = delegate.getSupportedOptions
  override def getSupportedAnnotationTypes(): java.util.Set[String] =
    delegate.getSupportedAnnotationTypes
  override def getSupportedSourceVersion(): SourceVersion = delegate.getSupportedSourceVersion
  override def getCompletions(
      element: Element,
      annotation: AnnotationMirror,
      member: ExecutableElement,
      userText: String
  ): java.lang.Iterable[Completion] =
    delegate.getCompletions(
      element,
      annotation,
      member,
      userText
    ).asInstanceOf[java.lang.Iterable[Completion]]

  override def init(processingEnv: ProcessingEnvironment): Unit =
    delegate.init(new TrackingProcessingEnvironment(processingEnv))

  override def process(
      annotations: java.util.Set[_ <: TypeElement],
      roundEnv: javax.annotation.processing.RoundEnvironment
  ): Boolean =
    delegate.process(annotations, roundEnv)
}

private final class TrackingProcessingEnvironment(delegate: ProcessingEnvironment)
    extends ProcessingEnvironment {
  private lazy val filer = new TrackingFiler(delegate)

  override def getOptions(): java.util.Map[String, String] = delegate.getOptions
  override def getMessager(): Messager = delegate.getMessager
  override def getFiler(): Filer = filer
  override def getElementUtils(): Elements = delegate.getElementUtils
  override def getTypeUtils(): Types = delegate.getTypeUtils
  override def getSourceVersion(): SourceVersion = delegate.getSourceVersion
  override def getLocale(): java.util.Locale = delegate.getLocale
}

private final class TrackingFiler(delegate: ProcessingEnvironment) extends Filer {
  private lazy val filer = delegate.getFiler
  private lazy val tracker = IncrementalAnnotationProcessing.currentTracker
  private lazy val trees =
    scala.util.Try(com.sun.source.util.Trees.instance(delegate)).toOption

  override def createSourceFile(
      name: CharSequence,
      originatingElements: Element*
  ): JavaFileObject = {
    val file = filer.createSourceFile(name, originatingElements*)
    new TrackingFilerJavaFileObject(file, tracker, ownersFor(originatingElements))
  }

  override def createClassFile(
      name: CharSequence,
      originatingElements: Element*
  ): JavaFileObject = {
    val file = filer.createClassFile(name, originatingElements*)
    new TrackingFilerJavaFileObject(file, tracker, ownersFor(originatingElements))
  }

  override def createResource(
      location: javax.tools.JavaFileManager.Location,
      pkg: CharSequence,
      relativeName: CharSequence,
      originatingElements: Element*
  ): FileObject = {
    val file = filer.createResource(location, pkg, relativeName, originatingElements*)
    new TrackingFilerFileObject(file, tracker, ownersFor(originatingElements))
  }

  override def getResource(
      location: javax.tools.JavaFileManager.Location,
      pkg: CharSequence,
      relativeName: CharSequence
  ): FileObject =
    filer.getResource(location, pkg, relativeName)

  private def ownersFor(originatingElements: Seq[Element]): Set[os.Path] =
    tracker
      .map(_.ownersForElements(originatingElements, trees, delegate.getElementUtils))
      .getOrElse(Set.empty)
}
