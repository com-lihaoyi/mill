package mill.javalib.zinc

import sbt.internal.inc.javac.{DirectToJarFileManager, SameFileFixFileManager}
import sbt.util.Logger
import sbt.util.Level
import xsbti.Reporter
import xsbti.VirtualFile
import xsbti.compile.{IncToolOptions, JavaCompiler as XJavaCompiler, Output}

import java.io.{OutputStream, PrintWriter, Writer}
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}
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

private[mill] object IncrementalTrackingJavaCompiler {
  def local: Option[XJavaCompiler] =
    Option(javax.tools.ToolProvider.getSystemJavaCompiler).map(new IncrementalTrackingJavaCompiler(_))
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
    log.debug("Attempting to call javac directly with incremental annotation processing tracking...")
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
        val fileManager =
          if (cleanedOptions.contains("-XDuseOptimizedZip=false"))
            fileManagerWithoutOptimizedZips(diagnostics)
          else standardFileManager
        val outputOption = sbt.internal.inc.CompilerArguments.outputOption(output)
        (fileManager, outputOption ++ cleanedOptions)
    }

    val jfiles = sources.toList.map(TrackingVirtualJavaFileObject(_))
    val customizedFileManager = {
      val maybeClassFileManager = incToolOptions.classFileManager()
      val base =
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
      base
    }

    var compileSuccess = false
    try {
      val success = compiler
        .getTask(
          logWriter,
          customizedFileManager,
          diagnostics,
          javacOptions.toList.asJava,
          null,
          jfiles.asJava
        )
        .call()
      compileSuccess = success && !diagnostics.hasErrors
    } finally {
      customizedFileManager.close()
      logger.flushLines(if (compileSuccess) Level.Warn else Level.Error)
    }
    compileSuccess
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

private final case class TrackingVirtualJavaFileObject(underlying: VirtualFile)
    extends javax.tools.SimpleJavaFileObject(
      new URI("vf", "tmp", s"/${underlying.id}", null),
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
    new TrackingJavaFileObject(output, sibling, classFileManager, tracker)
  }

  override def getFileForOutput(
      location: JavaFileManager.Location,
      packageName: String,
      relativeName: String,
      sibling: FileObject
  ): FileObject = {
    val output = super.getFileForOutput(location, packageName, relativeName, sibling)
    new TrackingPlainFileObject(output, sibling, tracker)
  }

  override def isSameFile(a: FileObject, b: FileObject): Boolean =
    TrackingFileManager.unwrap(a) == TrackingFileManager.unwrap(b)
}

private object TrackingFileManager {
  def unwrap(fileObject: FileObject): AnyRef =
    fileObject match {
      case tracked: TrackingJavaFileObject => tracked.delegate
      case tracked: TrackingPlainFileObject => tracked.delegate
      case other => other
    }
}

private final class TrackingJavaFileObject(
    val delegate: JavaFileObject,
    sibling: FileObject,
    classFileManager: Option[xsbti.compile.ClassFileManager],
    tracker: Option[IncrementalAnnotationProcessing.CompileTracker]
) extends ForwardingJavaFileObject[JavaFileObject](delegate) {
  val path: Option[Path] = IncrementalAnnotationProcessing.fileObjectPath(delegate)

  override def openWriter(): Writer = {
    notifyOpen()
    super.openWriter()
  }

  override def openOutputStream(): OutputStream = {
    notifyOpen()
    super.openOutputStream()
  }

  private def notifyOpen(): Unit = {
    classFileManager.foreach(_.generated(Array[VirtualFile](sbt.internal.inc.PlainVirtualFile(Paths.get(toUri)))))
    tracker.foreach(_.recordGenerated(delegate, Option(sibling)))
  }
}

private final class TrackingPlainFileObject(
    val delegate: FileObject,
    sibling: FileObject,
    tracker: Option[IncrementalAnnotationProcessing.CompileTracker]
) extends ForwardingFileObject[FileObject](delegate) {
  val path: Option[Path] = IncrementalAnnotationProcessing.fileObjectPath(delegate)

  override def openWriter(): Writer = {
    tracker.foreach(_.recordGenerated(delegate, Option(sibling)))
    super.openWriter()
  }

  override def openOutputStream(): OutputStream = {
    tracker.foreach(_.recordGenerated(delegate, Option(sibling)))
    super.openOutputStream()
  }
}
