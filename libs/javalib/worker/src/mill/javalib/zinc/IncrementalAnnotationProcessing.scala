package mill.javalib.zinc

import mill.api.daemon.Logger
import sbt.internal.inc.Analysis
import xsbti.{PathBasedFile, VirtualFile, VirtualFileRef}
import xsbti.compile.{CompileAnalysis, DefaultExternalHooks, ExternalHooks, FileHash}

import java.io.File
import java.nio.file.Path
import java.util.Optional
import javax.annotation.processing.Processor
import javax.lang.model.element.{Element, ElementKind, TypeElement}
import javax.lang.model.util.Elements
import javax.tools.FileObject
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using
import IncrementalAnnotationProcessingSeqCompat.*

private[mill] object IncrementalAnnotationProcessing {

  enum TrackingMode {
    case Isolating
    case Aggregating
  }

  enum Mode {
    case None
    case Disabled
    case Enabled(plan: CompilePlan)
  }

  case class SourceStamp(mtimeMillis: Long, size: Long) derives upickle.default.ReadWriter

  case class Snapshot(
      sourceStamps: Map[String, SourceStamp],
      products: Map[String, PersistedOwnership]
  ) derives upickle.default.ReadWriter

  enum PersistedOwnership derives upickle.default.ReadWriter {
    case Isolating(source: String)
    case Aggregating(sources: Seq[String])
    case Unknown

    def decode(workDir: os.Path): ProductOwnership =
      this match {
        case Isolating(source) =>
          ProductOwnership.Isolating(workDir / os.RelPath(source))
        case Aggregating(sources) =>
          ProductOwnership.Aggregating(sources.iterator.map(workDir / os.RelPath(_)).toSet)
        case Unknown =>
          ProductOwnership.Unknown
      }
  }

  enum Provenance {
    case Known(owners: Set[os.Path])
    case Unknown
  }

  enum ProductOwnership {
    case Isolating(source: os.Path)
    case Aggregating(sources: Set[os.Path])
    case Unknown

    def encode(workDir: os.Path): PersistedOwnership =
      this match {
        case Isolating(source) =>
          PersistedOwnership.Isolating(source.relativeTo(workDir).toString)
        case Aggregating(sources) =>
          PersistedOwnership.Aggregating(
            sources.toSeq.sortBy(_.toString).map(_.relativeTo(workDir).toString)
          )
        case Unknown =>
          PersistedOwnership.Unknown
      }
  }

  case class CompilePlan(
      sourceStamps: Map[os.Path, SourceStamp],
      staleProducts: Set[os.Path],
      requiresFullRecompile: Boolean,
      externalHooks: Option[ExternalHooks],
      tracker: CompileTracker
  )

  val MetadataPath = os.RelPath("META-INF/gradle/incremental.annotation.processors")
  val ProcessorServicePath = os.RelPath("META-INF/services/javax.annotation.processing.Processor")
  private val DynamicIsolatingOption = "org.gradle.annotation.processing.isolating"
  private val DynamicAggregatingOption = "org.gradle.annotation.processing.aggregating"

  private val trackerLocal = new ThreadLocal[CompileTracker]()

  def currentTracker: Option[CompileTracker] = Option(trackerLocal.get())

  def installTracker(tracker: CompileTracker): Unit = trackerLocal.set(tracker)

  def clearTracker(): Unit = trackerLocal.remove()

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
      val metadataPath = (processorPath ++ compileClasspath).distinct

      val activeProcessors = explicitProcessors(javacOptions).getOrElse {
        processorPath.iterator.flatMap(readProcessorServiceFile).toSeq
      }

      if (activeProcessors.isEmpty) Mode.None
      else {
        val metadata = metadataPath.iterator.flatMap(readProcessorMetadata).toMap
        val kinds = resolveTrackingModes(activeProcessors.toSet, metadata, processorPath, compileClasspath)
        kinds match {
          case None => Mode.Disabled
          case Some(activeKinds) =>
            val trackingMode =
              if (activeKinds.exists(_ == TrackingMode.Aggregating)) TrackingMode.Aggregating
              else TrackingMode.Isolating

            val sourceStamps = snapshotSources(sources)
            val classesDir = workDir / "classes"
            val previous = decodeSnapshot(readSnapshot(snapshotPath(workDir)), workDir, classesDir)
            val previousStamps = previous.sourceStamps
            val changedSources = changedSourcesSince(previousStamps, sourceStamps)
            val staleProducts = staleProductsFor(previous.products, previousStamps, sourceStamps)
            val removedSources = previousStamps.keySet -- sourceStamps.keySet
            val sourceSetDidChange = sourceSetChanged(previousStamps, sourceStamps)
            if (staleProducts.nonEmpty) {
              log.debug(
                s"Incremental annotation processing invalidated ${staleProducts.size} generated outputs"
              )
            }
            val requiresFullRecompile =
              (trackingMode == TrackingMode.Aggregating && sourceSetDidChange) ||
                (sourceSetDidChange && previous.products.valuesIterator.exists(_ == ProductOwnership.Unknown))
            Mode.Enabled(
              CompilePlan(
                sourceStamps = sourceStamps,
                staleProducts = staleProducts,
                requiresFullRecompile = requiresFullRecompile,
                externalHooks = Option.when(!requiresFullRecompile) {
                  externalHooks(staleProducts, changedSources, removedSources, sourceStamps.keySet)
                },
                tracker = new CompileTracker(trackingMode, sources.toSet, classesDir)
              )
            )
        }
      }
    }
  }

  def prepareBeforeCompile(staleProducts: Set[os.Path]): Unit =
    staleProducts.foreach(os.remove.all(_))

  def persist(
      workDir: os.Path,
      classesDir: os.Path,
      analysis: Analysis,
      auxiliaryClassFileExtensions: Seq[String],
      sourceStamps: Map[os.Path, SourceStamp],
      tracker: CompileTracker
  ): Unit = {
    val managedProducts = analysis.relations.allProducts.iterator.flatMap { product =>
      val path = os.Path(product.id)
      Iterator.single(path) ++ auxiliaryClassFileExtensions.iterator.map { ext =>
        path / os.up / s"${path.last.stripSuffix(".class")}.$ext"
      }
    }.toSet

    val tracked = mergeSnapshots(
      previous = decodeSnapshot(readSnapshot(snapshotPath(workDir)), workDir, classesDir),
      current = tracker.snapshot,
      managedProducts = managedProducts
    )

    writeSnapshot(
      snapshotPath(workDir),
      Snapshot(
        sourceStamps = sourceStamps.toSeq.sortBy(_._1.toString).map { case (path, stamp) =>
          path.relativeTo(workDir).toString -> stamp
        }.toMap,
        products = tracked.products.iterator
          .filter { case (product, _) => !managedProducts(product) && os.exists(product) }
          .map { case (product, ownership) =>
            product.relativeTo(classesDir).toString -> ownership.encode(workDir)
          }
          .toSeq
          .sortBy(_._1)
          .toMap
      )
    )
  }

  def previousExtraProducts(workDir: os.Path): Set[os.Path] = {
    val classesDir = workDir / "classes"
    decodeSnapshot(readSnapshot(snapshotPath(workDir)), workDir, classesDir).products.keySet
  }

  def snapshotPath(workDir: os.Path): os.Path =
    workDir / "incremental-annotation-processing.json"

  def readSnapshot(path: os.Path): Snapshot =
    if (!os.exists(path)) Snapshot(Map.empty, Map.empty)
    else
      scala.util.Try(upickle.default.read[Snapshot](os.read(path)))
        .getOrElse(Snapshot(Map.empty, Map.empty))

  def writeSnapshot(path: os.Path, snapshot: Snapshot): Unit = {
    os.makeDir.all(path / os.up)
    os.write.over(path, upickle.default.write(snapshot, indent = 2))
  }

  def externalHooks(
      staleProducts: Set[os.Path],
      changedSources: Set[os.Path],
      removedSources: Set[os.Path],
      currentSources: Set[os.Path]
  ): ExternalHooks = {
    val removedProducts = staleProducts.map(p => VirtualFileRef.of(p.toString)).asJava
    val changedSourcesOpt =
      if (changedSources.nonEmpty || removedSources.nonEmpty)
        Optional.of(new xsbti.compile.Changes[VirtualFileRef] {
          override def getAdded(): java.util.Set[VirtualFileRef] =
            Set.empty[VirtualFileRef].asJava
          override def getRemoved(): java.util.Set[VirtualFileRef] =
            removedSources.map(p => VirtualFileRef.of(p.toString)).asJava
          override def getChanged(): java.util.Set[VirtualFileRef] =
            changedSources.map(p => VirtualFileRef.of(p.toString)).asJava
          override def getUnmodified(): java.util.Set[VirtualFileRef] =
            (currentSources -- changedSources).map(p => VirtualFileRef.of(p.toString)).asJava
          override def isEmpty(): java.lang.Boolean =
            java.lang.Boolean.valueOf(changedSources.isEmpty && removedSources.isEmpty)
        })
      else Optional.empty[xsbti.compile.Changes[VirtualFileRef]]()

    val lookup = new ExternalHooks.Lookup {
      override def getChangedSources(
          previousAnalysis: CompileAnalysis
      ): Optional[xsbti.compile.Changes[VirtualFileRef]] = changedSourcesOpt

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

  def explicitProcessors(javacOptions: Seq[String]): Option[Seq[String]] =
    parseSimpleOption(javacOptions, "-processor", "-processor")
      .map(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toSeq)

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

  def readProcessorServiceFile(path: os.Path): Seq[String] =
    readTextFile(path, ProcessorServicePath)

  def readProcessorMetadata(path: os.Path): Seq[(String, String)] =
    readTextFile(path, MetadataPath)
      .collect {
        case s"$processor,$kind" if processor.trim.nonEmpty && kind.trim.nonEmpty =>
          processor.trim -> kind.trim.toLowerCase(java.util.Locale.ROOT)
      }

  def readTextFile(root: os.Path, relPath: os.RelPath): Seq[String] = {
    def parseEntries(content: String): Seq[String] =
      content.linesIterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).toSeq

    if (os.isDir(root)) Option(root / relPath).filter(os.exists(_)).map(os.read(_))
      .map(parseEntries)
      .getOrElse(Nil)
    else if (os.isFile(root) && root.ext == "jar") {
      Using.resource(os.zip.open(root)) { zip =>
        Option.when(os.exists(zip / relPath))(parseEntries(os.read(zip / relPath))).getOrElse(Nil)
      }
    } else Nil
  }

  def fileObjectPath(fileObject: FileObject): Option[Path] =
    fileObject match {
      case tracked: TrackingOutputObject => tracked.path
      case _ =>
        reflectedUnderlyingVirtualFile(fileObject)
          .collect { case pathBased: PathBasedFile => pathBased.toPath.toAbsolutePath.normalize() }
          .orElse(reflectedNestedFileObject(fileObject).flatMap(fileObjectPath))
          .orElse {
            Option(fileObject.toUri)
              .filter(_.getScheme == "file")
              .map(uri => Path.of(uri).toAbsolutePath.normalize())
          }
    }

  private def snapshotSources(sources: Seq[os.Path]): Map[os.Path, SourceStamp] =
    sources.iterator.map { source =>
      val stat = os.stat(source)
      source -> SourceStamp(stat.mtime.toMillis, stat.size)
    }.toMap

  private def staleProductsFor(
      previousProducts: Map[os.Path, ProductOwnership],
      previousStamps: Map[os.Path, SourceStamp],
      currentStamps: Map[os.Path, SourceStamp]
  ): Set[os.Path] = {
    val changedSources = changedSourcesSince(previousStamps, currentStamps)
    val anySourceChanged = sourceSetChanged(previousStamps, currentStamps)
    val removedSources = previousStamps.keySet -- currentStamps.keySet

    previousProducts.iterator.collect {
      case (product, ProductOwnership.Isolating(source))
          if changedSources.contains(source) || removedSources.contains(source) =>
        product
      case (product, ProductOwnership.Aggregating(sources))
          if sources.exists(changedSources.contains) || sources.exists(removedSources.contains) =>
        product
      case (product, ProductOwnership.Unknown) if anySourceChanged =>
        product
    }.toSet
  }

  private def decodeSnapshot(
      snapshot: Snapshot,
      workDir: os.Path,
      classesDir: os.Path
  ): DecodedSnapshot =
    DecodedSnapshot(
      sourceStamps = snapshot.sourceStamps.iterator.map { case (path, stamp) =>
        (workDir / os.RelPath(path)) -> stamp
      }.toMap,
      products = snapshot.products.iterator.map { case (product, ownership) =>
        (classesDir / os.RelPath(product)) -> ownership.decode(workDir)
      }.toMap
    )

  private def mergeSnapshots(
      previous: DecodedSnapshot,
      current: TrackerSnapshot,
      managedProducts: Set[os.Path]
  ): TrackerSnapshot = {
    def keepUntouched(product: os.Path): Boolean =
      !managedProducts(product) && !current.touchedProducts(product) && os.exists(product)

    def keepCurrent(product: os.Path): Boolean =
      !managedProducts(product) && os.exists(product)

    TrackerSnapshot(
      products =
        previous.products.filter { case (product, _) => keepUntouched(product) } ++
          current.products.filter { case (product, _) => keepCurrent(product) },
      touchedProducts = current.touchedProducts
    )
  }

  private def resolveTrackingModes(
      activeProcessors: Set[String],
      metadata: Map[String, String],
      processorPath: Seq[os.Path],
      compileClasspath: Seq[os.Path]
  ): Option[Set[TrackingMode]] = {
    val classpath = (processorPath ++ compileClasspath).distinct
    activeProcessors.iterator.map { processor =>
      metadata.get(processor) match {
        case Some("isolating") => Some(TrackingMode.Isolating)
        case Some("aggregating") => Some(TrackingMode.Aggregating)
        case Some("dynamic") => resolveDynamicTrackingMode(processor, classpath)
        case _ => None
      }
    }.toSeq.sequence.map(_.toSet)
  }

  private def reflectedUnderlyingVirtualFile(fileObject: FileObject): Option[VirtualFile] =
    reflectedValues(fileObject).collectFirst { case virtual: VirtualFile => virtual }

  private def reflectedNestedFileObject(fileObject: FileObject): Option[FileObject] =
    reflectedValues(fileObject).collectFirst {
      case nested: FileObject if nested ne fileObject => nested
    }

  private def reflectedValues(value: AnyRef): Iterator[AnyRef] =
    Iterator
      .iterate(Option(value.getClass))(_.flatMap(cls => Option(cls.getSuperclass)))
      .takeWhile(_.nonEmpty)
      .flatten
      .flatMap(_.getDeclaredFields.iterator)
      .flatMap { field =>
        scala.util.Try {
          field.setAccessible(true)
          field.get(value)
        }.toOption.collect { case ref: AnyRef => ref }
      }

  private def changedSourcesSince(
      previousStamps: Map[os.Path, SourceStamp],
      currentStamps: Map[os.Path, SourceStamp]
  ): Set[os.Path] =
    previousStamps.iterator.collect {
      case (source, stamp) if currentStamps.get(source) != Some(stamp) => source
    }.toSet

  private def sourceSetChanged(
      previousStamps: Map[os.Path, SourceStamp],
      currentStamps: Map[os.Path, SourceStamp]
  ): Boolean =
    changedSourcesSince(previousStamps, currentStamps).nonEmpty ||
      (currentStamps.keySet -- previousStamps.keySet).nonEmpty

  private def resolveDynamicTrackingMode(
      processorClassName: String,
      classpath: Seq[os.Path]
  ): Option[TrackingMode] = {
    val urls = classpath.iterator.map(_.toIO.toURI.toURL).toArray
    Using.resource(new java.net.URLClassLoader(urls, getClass.getClassLoader)) { loader =>
      scala.util
        .Try {
          val cls = loader.loadClass(processorClassName)
          val processor = cls.getDeclaredConstructor().newInstance().asInstanceOf[Processor]
          val options = processor.getSupportedOptions.asScala
          if (options.contains(DynamicAggregatingOption)) TrackingMode.Aggregating
          else if (options.contains(DynamicIsolatingOption)) TrackingMode.Isolating
          else null
        }
        .toOption
        .filter(_ != null)
    }
  }

  final class CompileTracker(
      trackingMode: TrackingMode,
      sources: Set[os.Path],
      classesDir: os.Path
  ) {
    private val sourceOwners =
      sources.iterator.map(p => p.toNIO.toAbsolutePath.normalize() -> Provenance.Known(Set(p))).toMap
    private val sourceMetadata = sources.iterator.map(SourceMetadata.apply).toSeq
    private val generatedOwners = mutable.LinkedHashMap.empty[Path, Provenance]
    private val products = mutable.LinkedHashMap.empty[os.Path, ProductOwnership]
    private val touchedProducts = mutable.LinkedHashSet.empty[os.Path]

    def ownersForElements(
        elements: Iterable[Element],
        trees: Option[com.sun.source.util.Trees],
        elementUtils: Elements
    ): Set[os.Path] =
      elements.iterator.flatMap(ownerForElement(_, trees, elementUtils)).toSet

    def recordOwnedGenerated(fileObject: FileObject, owners: Set[os.Path]): Unit =
      recordGenerated(fileObject, _ => ownershipForOwners(owners))

    def recordSiblingGenerated(fileObject: FileObject, sibling: Option[FileObject]): Unit =
      recordGenerated(fileObject, previous => previous.getOrElse(ownerFor(sibling.flatMap(fileObjectPath))))

    private def recordGenerated(
        fileObject: FileObject,
        resolveProvenance: Option[Provenance] => Provenance
    ): Unit = {
      for (outputPath <- fileObjectPath(fileObject)) {
        val provenance = resolveProvenance(generatedOwners.get(outputPath))
        generatedOwners(outputPath) = provenance
        val outputOsPath = os.Path(outputPath)
        if (outputOsPath.startsWith(classesDir)) {
          touchedProducts += outputOsPath
          products(outputOsPath) = ownershipFor(provenance)
        }
      }
    }

    def cleanupFailedCompile(): Unit =
      touchedProducts.foreach(os.remove.all(_))

    def snapshot: TrackerSnapshot =
      TrackerSnapshot(
        products = products.toMap,
        touchedProducts = touchedProducts.toSet
      )

    private def ownerFor(siblingPath: Option[Path]): Provenance =
      siblingPath
        .flatMap(path => sourceOwners.get(path).orElse(generatedOwners.get(path)))
        .getOrElse(Provenance.Unknown)

    private def ownershipForOwners(owners: Set[os.Path]): Provenance =
      if (owners.nonEmpty) Provenance.Known(owners) else Provenance.Unknown

    private def ownershipFor(provenance: Provenance): ProductOwnership =
      provenance match {
        case Provenance.Known(owners) if trackingMode == TrackingMode.Isolating && owners.size == 1 =>
          ProductOwnership.Isolating(owners.head)
        case Provenance.Known(owners) if owners.nonEmpty =>
          ProductOwnership.Aggregating(owners)
        case _ =>
          ProductOwnership.Unknown
      }

    private def ownerForElement(
        element: Element,
        trees: Option[com.sun.source.util.Trees],
        elementUtils: Elements
    ): Option[os.Path] = {
      val topLevelType = Iterator
        .iterate(Option(element))(_.flatMap(e => Option(e.getEnclosingElement)))
        .takeWhile(_.nonEmpty)
        .flatten
        .collectFirst {
          case tpe: TypeElement
              if Option(tpe.getEnclosingElement).exists(_.getKind == ElementKind.PACKAGE) =>
            tpe
        }

      trees
        .flatMap(_.getPath(element) match {
          case null => None
          case path =>
            Option(path.getCompilationUnit)
              .flatMap(unit => Option(unit.getSourceFile))
              .flatMap(fileObjectPath)
              .map(os.Path(_))
        })
        .orElse {
          topLevelType.flatMap { tpe =>
            val qualifiedName = elementUtils.getBinaryName(tpe).toString
            val packageName =
              Option(elementUtils.getPackageOf(tpe))
                .filterNot(_.isUnnamed)
                .map(_.getQualifiedName.toString)
                .getOrElse("")
            val simpleName =
              qualifiedName.stripPrefix(if (packageName.isEmpty) "" else packageName + ".").takeWhile(_ != '$')
            val matches = sourceMetadata.filter(_.matchesType(packageName, simpleName))
            Option.when(matches.size == 1)(matches.head.path)
          }
        }
    }
  }

  case class DecodedSnapshot(
      sourceStamps: Map[os.Path, SourceStamp],
      products: Map[os.Path, ProductOwnership]
  )

  case class TrackerSnapshot(
      products: Map[os.Path, ProductOwnership],
      touchedProducts: Set[os.Path]
  )

  case class SourceMetadata(path: os.Path, simpleName: String, packageName: String) {
    def matchesType(candidatePackageName: String, candidateSimpleName: String): Boolean =
      packageName == candidatePackageName && simpleName == candidateSimpleName
  }

  object SourceMetadata {
    def apply(path: os.Path): SourceMetadata = {
      val simpleName = path.baseName
      val parentSegments = path.segments.toSeq
      val srcIndex = parentSegments.indexOf("src")
      val packageSegments =
        if (srcIndex >= 0) parentSegments.drop(srcIndex + 1).dropRight(1)
        else parentSegments.dropRight(1)
      val packageName = packageSegments.mkString(".")
      SourceMetadata(path, simpleName, packageName)
    }
  }
}

private object IncrementalAnnotationProcessingSeqCompat {
  extension [A](items: Seq[Option[A]])
    def sequence: Option[Seq[A]] =
      items.foldRight(Option(Seq.empty[A])) { (item, acc) =>
        for {
          value <- item
          rest <- acc
        } yield value +: rest
      }
}
