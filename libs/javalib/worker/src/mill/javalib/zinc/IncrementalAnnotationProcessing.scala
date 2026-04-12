package mill.javalib.zinc

import mill.api.daemon.Logger
import mill.api.daemon.internal.internal
import sbt.internal.inc.Analysis
import xsbti.VirtualFileRef
import xsbti.compile.{CompileAnalysis, DefaultExternalHooks, ExternalHooks, FileHash}

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using

@internal
object IncrementalAnnotationProcessing {

  sealed trait Mode
  object Mode {
    case object None extends Mode
    case class Disabled(reason: String) extends Mode
    case class Enabled(state: State) extends Mode
  }

  case class State(
      sourceFingerprint: String,
      sourceSnapshotChanged: Boolean,
      previousExtraProducts: Set[os.Path],
      externalHooks: ExternalHooks
  ) {
    def prepareBeforeCompile(): Unit = {
      if (sourceSnapshotChanged) {
        previousExtraProducts.foreach { path =>
          if (os.exists(path)) os.remove.all(path)
        }
      }
    }

    def persist(
        workDir: os.Path,
        classesDir: os.Path,
        analysis: Analysis,
        auxiliaryClassFileExtensions: Seq[String]
    ): Unit = {
      val managedProducts = analysis.relations.allProducts.iterator.flatMap { product =>
        val path = os.Path(product.id)
        Iterator.single(path) ++ auxiliaryClassFileExtensions.iterator.map { ext =>
          path / os.up / s"${path.last.stripSuffix(".class")}.$ext"
        }
      }.toSet

      val extraProducts =
        if (os.exists(classesDir)) {
          os.walk(classesDir).iterator.filter(os.isFile).toSet -- managedProducts
        } else Set.empty[os.Path]

      writeSnapshot(snapshotPath(workDir), sourceFingerprint, extraProducts)
    }
  }

  private val MetadataPath = os.RelPath("META-INF/gradle/incremental.annotation.processors")
  private val ProcessorServicePath = os.RelPath("META-INF/services/javax.annotation.processing.Processor")
  private val SupportedKinds = Set("isolating", "aggregating", "dynamic")

  def detect(
      javacOptions: Seq[String],
      compileClasspath: Seq[os.Path],
      sources: Seq[os.Path],
      workDir: os.Path,
      incrementalCompilation: Boolean,
      log: Logger.Actions
  ): Mode = {
    if (!incrementalCompilation || hasFlag(javacOptions, "-proc:none")) Mode.None
    else {
      val processorPath = parsePathOption(javacOptions, "-processorpath", "--processor-path")
        .map(_.map(os.Path(_, os.pwd)))
        .getOrElse(compileClasspath)

      val activeProcessors = explicitProcessors(javacOptions).getOrElse {
        processorPath.iterator.flatMap(readProcessorServiceFile).toSet
      }

      if (activeProcessors.isEmpty) Mode.None
      else {
        val metadata = processorPath.iterator.flatMap(readProcessorMetadata).toMap
        val unsupported = activeProcessors.toSeq.sorted.collect {
          case processor if !metadata.get(processor).exists(SupportedKinds) => processor
        }

        if (unsupported.nonEmpty) {
          Mode.Disabled(
            s"annotation processors do not declare supported incremental metadata: ${unsupported.mkString(", ")}"
          )
        } else {
          val sourceFingerprint = fingerprintSources(sources)
          val previous = readSnapshot(snapshotPath(workDir))
          val previousExtraProducts =
            previous.products.map(workDir / "classes" / os.RelPath(_)).toSet
          val sourceSnapshotChanged = previous.sourceFingerprint.exists(_ != sourceFingerprint)
          if (sourceSnapshotChanged && previousExtraProducts.nonEmpty) {
            log.debug(
              s"Incremental annotation processing invalidated ${previousExtraProducts.size} extra generated outputs"
            )
          }
          Mode.Enabled(
            State(
              sourceFingerprint = sourceFingerprint,
              sourceSnapshotChanged = sourceSnapshotChanged,
              previousExtraProducts = previousExtraProducts,
              externalHooks = externalHooks(previousExtraProducts, sourceSnapshotChanged)
            )
          )
        }
      }
    }
  }

  def clearSnapshot(workDir: os.Path): Unit = {
    val path = snapshotPath(workDir)
    if (os.exists(path)) os.remove(path)
  }

  private def externalHooks(
      previousExtraProducts: Set[os.Path],
      sourceSnapshotChanged: Boolean
  ): ExternalHooks = {
    val removedProducts =
      if (sourceSnapshotChanged) previousExtraProducts.map(p => VirtualFileRef.of(p.toString)).asJava
      else Set.empty[VirtualFileRef].asJava

    val lookup = new ExternalHooks.Lookup {
      override def getChangedSources(
          previousAnalysis: CompileAnalysis
      ): Optional[xsbti.compile.Changes[VirtualFileRef]] = Optional.empty()

      override def getChangedBinaries(
          previousAnalysis: CompileAnalysis
      ): Optional[java.util.Set[VirtualFileRef]] = Optional.empty()

      override def getRemovedProducts(
          previousAnalysis: CompileAnalysis
      ): Optional[java.util.Set[VirtualFileRef]] =
        if (removedProducts.isEmpty) Optional.empty()
        else Optional.of(removedProducts)

      override def shouldDoIncrementalCompilation(
          changedClasses: java.util.Set[String],
          previousAnalysis: CompileAnalysis
      ): Boolean = true

      override def hashClasspath(
          classpath: Array[xsbti.VirtualFile]
      ): Optional[Array[FileHash]] = Optional.empty()
    }

    new DefaultExternalHooks(Optional.of(lookup), Optional.empty())
  }

  private case class Snapshot(sourceFingerprint: Option[String], products: Seq[String])

  private def snapshotPath(workDir: os.Path): os.Path =
    workDir / "incremental-annotation-processing.txt"

  private def readSnapshot(path: os.Path): Snapshot = {
    if (!os.exists(path)) Snapshot(None, Nil)
    else {
      val lines = os.read.lines(path)
      val sourceFingerprint = lines.collectFirst { case line if line.startsWith("source\t") =>
        line.stripPrefix("source\t")
      }
      val products = lines.collect { case line if line.startsWith("product\t") =>
        line.stripPrefix("product\t")
      }
      Snapshot(sourceFingerprint, products)
    }
  }

  private def writeSnapshot(path: os.Path, sourceFingerprint: String, products: Set[os.Path]): Unit = {
    os.makeDir.all(path / os.up)
    val classesDir = path / os.up / "classes"
    val content =
      Seq(s"source\t$sourceFingerprint") ++
        products.toSeq.sortBy(_.toString).map(_.relativeTo(classesDir).toString).map("product\t" + _)
    os.write.over(path, content.mkString("", "\n", "\n"))
  }

  private def fingerprintSources(sources: Seq[os.Path]): String =
    sources.toSeq.sorted.map { source =>
      val stat = os.stat(source)
      s"${source.toString}\t${stat.mtime.toMillis}\t${stat.size}"
    }.mkString("\n")

  private def explicitProcessors(javacOptions: Seq[String]): Option[Set[String]] =
    parseSimpleOption(javacOptions, "-processor", "--processor")
      .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet)

  private def parsePathOption(
      javacOptions: Seq[String],
      short: String,
      long: String
  ): Option[Seq[String]] =
    parseSimpleOption(javacOptions, short, long)
      .map(_.split(File.pathSeparator).toSeq.filter(_.nonEmpty))

  private def parseSimpleOption(
      javacOptions: Seq[String],
      short: String,
      long: String
  ): Option[String] = {
    javacOptions.sliding(2).collectFirst {
      case Seq(flag, value) if flag == short || flag == long => value
    }.orElse {
      javacOptions.collectFirst {
        case option if option.startsWith(short + "=") => option.stripPrefix(short + "=")
        case option if option.startsWith(long + "=") => option.stripPrefix(long + "=")
      }
    }
  }

  private def hasFlag(javacOptions: Seq[String], flag: String): Boolean =
    javacOptions.contains(flag)

  private def readProcessorServiceFile(path: os.Path): Set[String] =
    readTextFile(path, ProcessorServicePath)
      .toSeq
      .flatMap(parseEntries)
      .toSet

  private def readProcessorMetadata(path: os.Path): Seq[(String, String)] =
    readTextFile(path, MetadataPath)
      .toSeq
      .flatMap(parseEntries)
      .flatMap { line =>
        line.split(',').map(_.trim).toList match {
          case processor :: kind :: _ if processor.nonEmpty && kind.nonEmpty =>
            Some(processor -> kind.toLowerCase(java.util.Locale.ROOT))
          case _ => None
        }
      }

  private def parseEntries(content: String): Seq[String] =
    content.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).toSeq

  private def readTextFile(root: os.Path, relPath: os.RelPath): Option[String] = {
    if (os.isDir(root)) {
      val path = root / relPath
      Option.when(os.exists(path))(os.read(path))
    } else if (os.isFile(root) && root.ext == "jar") {
      Using.resource(new JarFile(root.toIO)) { jar =>
        Option(jar.getJarEntry(relPath.toString)).map { entry =>
          val in = jar.getInputStream(entry)
          try new String(in.readAllBytes(), StandardCharsets.UTF_8)
          finally in.close()
        }
      }
    } else None
  }
}
