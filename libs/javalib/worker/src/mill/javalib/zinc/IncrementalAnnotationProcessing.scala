package mill.javalib.zinc

import mill.api.daemon.Logger
import sbt.internal.inc.Analysis
import xsbti.VirtualFileRef
import xsbti.compile.{CompileAnalysis, DefaultExternalHooks, ExternalHooks, FileHash}

import java.io.File
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.util.Using

private[mill] object IncrementalAnnotationProcessing {

  enum Mode {
    case None
    case Disabled(reason: String)
    case Enabled(
        sourceFingerprint: String,
        sourceSnapshotChanged: Boolean,
        previousExtraProducts: Set[os.Path],
        externalHooks: ExternalHooks
    )
  }

  val MetadataPath = os.RelPath("META-INF/gradle/incremental.annotation.processors")
  val ProcessorServicePath = os.RelPath("META-INF/services/javax.annotation.processing.Processor")
  val SupportedKinds = Set("isolating", "aggregating", "dynamic")

  def detect(
      javacOptions: Seq[String],
      compileClasspath: Seq[os.Path],
      sources: Seq[os.Path],
      workDir: os.Path,
      incrementalCompilation: Boolean,
      log: Logger.Actions
  ): Mode = {
    if (!incrementalCompilation || javacOptions.contains("-proc:none")) Mode.None
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
            sourceFingerprint = sourceFingerprint,
            sourceSnapshotChanged = sourceSnapshotChanged,
            previousExtraProducts = previousExtraProducts,
            externalHooks = externalHooks(previousExtraProducts, sourceSnapshotChanged)
          )
        }
      }
    }
  }

  def prepareBeforeCompile(
      sourceSnapshotChanged: Boolean,
      previousExtraProducts: Set[os.Path]
  ): Unit = {
    if (sourceSnapshotChanged) previousExtraProducts.foreach(os.remove.all(_))
  }

  def persist(
      workDir: os.Path,
      classesDir: os.Path,
      analysis: Analysis,
      auxiliaryClassFileExtensions: Seq[String],
      sourceFingerprint: String
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

  def externalHooks(
      previousExtraProducts: Set[os.Path],
      sourceSnapshotChanged: Boolean
  ): ExternalHooks = {
    val removedProducts =
      if (sourceSnapshotChanged)
        previousExtraProducts.map(p => VirtualFileRef.of(p.toString)).asJava
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

  case class Snapshot(sourceFingerprint: Option[String], products: Seq[String])
      derives upickle.default.ReadWriter

  def snapshotPath(workDir: os.Path): os.Path =
    workDir / "incremental-annotation-processing.json"

  def readSnapshot(path: os.Path): Snapshot = {
    if (!os.exists(path)) Snapshot(None, Nil)
    else
      scala.util.Try(upickle.default.read[Snapshot](os.read(path))).getOrElse(Snapshot(None, Nil))
  }

  def previousExtraProducts(workDir: os.Path): Set[os.Path] = {
    val snapshot = readSnapshot(snapshotPath(workDir))
    snapshot.products.map(workDir / "classes" / os.RelPath(_)).toSet
  }

  def writeSnapshot(path: os.Path, sourceFingerprint: String, products: Set[os.Path]): Unit = {
    os.makeDir.all(path / os.up)
    val classesDir = path / os.up / "classes"
    val snapshot = Snapshot(
      sourceFingerprint = Some(sourceFingerprint),
      products = products.toSeq.sortBy(_.toString).map(_.relativeTo(classesDir).toString)
    )
    os.write.over(path, upickle.default.write(snapshot, indent = 2))
  }

  def fingerprintSources(sources: Seq[os.Path]): String =
    sources.toSeq.sorted.map { source =>
      val stat = os.stat(source)
      s"${source.toString}\t${stat.mtime.toMillis}\t${stat.size}"
    }.mkString("\n")

  def explicitProcessors(javacOptions: Seq[String]): Option[Set[String]] =
    parseSimpleOption(javacOptions, "-processor", "-processor")
      .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSet)

  def parsePathOption(javacOptions: Seq[String], short: String, long: String): Option[Seq[String]] =
    parseSimpleOption(javacOptions, short, long)
      .map(_.split(File.pathSeparator).toSeq.filter(_.nonEmpty))

  def parseSimpleOption(
      javacOptions: Seq[String],
      short: String,
      long: String
  ): Option[String] = {
    javacOptions.sliding(2).collectFirst {
      case Seq(flag, value) if flag == short || flag == long => value
    }.orElse {
      javacOptions.collectFirst {
        case s"${`short`}=$option" => option
        case s"${`long`}=$option" => option
      }
    }
  }

  def readProcessorServiceFile(path: os.Path): Set[String] =
    readTextFile(path, ProcessorServicePath).toSet

  def readProcessorMetadata(path: os.Path): Seq[(String, String)] =
    readTextFile(path, MetadataPath)
      .collect {
        case s"$processor,$kind" if processor.trim.nonEmpty && kind.trim.nonEmpty =>
          processor.trim -> kind.trim.toLowerCase(java.util.Locale.ROOT)
      }

  // Gradle documents this metadata file format here:
  // https://docs.gradle.org/current/userguide/java_plugin.html#sec:incremental_annotation_processing
  def readTextFile(root: os.Path, relPath: os.RelPath): Seq[String] = {
    def parseEntries(content: String): Seq[String] =
      content.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).toSeq

    if (os.isDir(root)) Option(root / relPath).filter(os.exists(_)).map(os.read(_))
      .map(parseEntries)
      .getOrElse(Nil)
    else if (os.isFile(root) && root.ext == "jar") {
      Using.resource(os.zip.open(root)) { zip =>
        Option.when(
          os.exists(zip / relPath)
        ) { parseEntries(os.read(zip / relPath)) }.getOrElse(Nil)
      }
    } else Nil
  }
}
