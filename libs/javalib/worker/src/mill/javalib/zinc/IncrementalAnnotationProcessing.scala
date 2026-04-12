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
    case Enabled(
        trackingMode: TrackingMode,
        sourceStamps: Map[os.Path, SourceStamp],
        staleProducts: Set[os.Path],
        forceFullRecompile: Boolean,
        forcedChangedSources: Set[os.Path],
        removedSources: Set[os.Path],
        externalHooks: ExternalHooks,
        tracker: CompileTracker
    )
  }

  case class SourceStamp(mtimeMillis: Long, size: Long) derives upickle.default.ReadWriter

  case class Snapshot(
      sourceStamps: Map[String, SourceStamp],
      isolatingProducts: Map[String, Seq[String]],
      aggregatingProducts: Map[String, Seq[String]],
      unknownProducts: Seq[String]
  ) derives upickle.default.ReadWriter

  enum Provenance {
    case Known(owners: Set[os.Path])
    case Unknown
  }

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
        processorPath.iterator.flatMap(readProcessorServiceFile).toSet
      }

      if (activeProcessors.isEmpty) Mode.None
      else {
        val metadata = metadataPath.iterator.flatMap(readProcessorMetadata).toMap
        val kinds = resolveTrackingModes(activeProcessors, metadata, processorPath, compileClasspath)
        kinds match {
          case None => Mode.Disabled
          case Some(activeKinds) =>
            val trackingMode =
              if (activeKinds.exists(_ == TrackingMode.Aggregating)) TrackingMode.Aggregating
              else TrackingMode.Isolating

            val sourceStamps = snapshotSources(sources)
            val previous = readSnapshot(snapshotPath(workDir))
            val previousStamps = previous.sourceStamps.iterator.map { case (path, stamp) =>
              workDir / os.RelPath(path) -> stamp
            }.toMap
            val staleProducts = staleProductsFor(previous, sourceStamps, workDir)
            val removedSources = previousStamps.keySet -- sourceStamps.keySet
            val sourceSetDidChange = sourceSetChanged(previousStamps, sourceStamps)
            val forcedChangedSources =
              forcedChangedSourcesFor(previous, sourceStamps.keySet, removedSources, workDir)
            if (staleProducts.nonEmpty) {
              log.debug(
                s"Incremental annotation processing invalidated ${staleProducts.size} generated outputs"
              )
            }
            Mode.Enabled(
              trackingMode = trackingMode,
              sourceStamps = sourceStamps,
              staleProducts = staleProducts,
              forceFullRecompile =
                (previous.unknownProducts.nonEmpty && sourceSetDidChange) ||
                  (trackingMode == TrackingMode.Aggregating && sourceSetDidChange),
              forcedChangedSources = forcedChangedSources,
              removedSources = removedSources,
              externalHooks =
                externalHooks(staleProducts, forcedChangedSources, removedSources, sourceStamps.keySet),
              tracker = new CompileTracker(trackingMode, sources.toSet, workDir / "classes")
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

    val tracked = tracker.snapshot
    val filteredIsolating = tracked.isolatingProducts.view
      .mapValues(_.filterNot(managedProducts))
      .filter(_._2.nonEmpty)
      .map { case (source, products) => source.relativeTo(workDir).toString -> products }
      .toMap

    writeSnapshot(
      snapshotPath(workDir),
      Snapshot(
        sourceStamps = sourceStamps.toSeq.sortBy(_._1.toString).map { case (path, stamp) =>
          path.relativeTo(workDir).toString -> stamp
        }.toMap,
        isolatingProducts = filteredIsolating.view
          .mapValues(_.toSeq.sortBy(_.toString).map(_.relativeTo(classesDir).toString))
          .toMap,
        aggregatingProducts = tracked.aggregatingProducts.view
          .filter { case (product, _) => !managedProducts(product) }
          .mapValues(_.toSeq.sortBy(_.toString).map(_.relativeTo(workDir).toString))
          .map { case (product, owners) => product.relativeTo(classesDir).toString -> owners }
          .toMap,
        unknownProducts = tracked.unknownProducts
          .filterNot(managedProducts)
          .toSeq
          .sortBy(_.toString)
          .map(_.relativeTo(classesDir).toString)
      )
    )
  }

  def previousExtraProducts(workDir: os.Path): Set[os.Path] = {
    val snapshot = readSnapshot(snapshotPath(workDir))
    val classesDir = workDir / "classes"
    snapshot.isolatingProducts.values.flatten.map(classesDir / os.RelPath(_)).toSet ++
      snapshot.aggregatingProducts.keys.map(classesDir / os.RelPath(_)) ++
      snapshot.unknownProducts.map(classesDir / os.RelPath(_))
  }

  def snapshotPath(workDir: os.Path): os.Path =
    workDir / "incremental-annotation-processing.json"

  def readSnapshot(path: os.Path): Snapshot =
    if (!os.exists(path)) Snapshot(Map.empty, Map.empty, Map.empty, Nil)
    else
      scala.util.Try(upickle.default.read[Snapshot](os.read(path)))
        .getOrElse(Snapshot(Map.empty, Map.empty, Map.empty, Nil))

  def writeSnapshot(path: os.Path, snapshot: Snapshot): Unit = {
    os.makeDir.all(path / os.up)
    os.write.over(path, upickle.default.write(snapshot, indent = 2))
  }

  def externalHooks(
      staleProducts: Set[os.Path],
      forcedChangedSources: Set[os.Path],
      removedSources: Set[os.Path],
      currentSources: Set[os.Path]
  ): ExternalHooks = {
    val removedProducts = staleProducts.map(p => VirtualFileRef.of(p.toString)).asJava
    val changedSources =
      if (forcedChangedSources.nonEmpty || removedSources.nonEmpty)
        Optional.of(new xsbti.compile.Changes[VirtualFileRef] {
          override def getAdded(): java.util.Set[VirtualFileRef] =
            Set.empty[VirtualFileRef].asJava
          override def getRemoved(): java.util.Set[VirtualFileRef] =
            removedSources.map(p => VirtualFileRef.of(p.toString)).asJava
          override def getChanged(): java.util.Set[VirtualFileRef] =
            forcedChangedSources.map(p => VirtualFileRef.of(p.toString)).asJava
          override def getUnmodified(): java.util.Set[VirtualFileRef] =
            (currentSources -- forcedChangedSources).map(p => VirtualFileRef.of(p.toString)).asJava
          override def isEmpty(): java.lang.Boolean =
            java.lang.Boolean.valueOf(forcedChangedSources.isEmpty && removedSources.isEmpty)
        })
      else Optional.empty[xsbti.compile.Changes[VirtualFileRef]]()

    val lookup = new ExternalHooks.Lookup {
      override def getChangedSources(
          previousAnalysis: CompileAnalysis
      ): Optional[xsbti.compile.Changes[VirtualFileRef]] = changedSources

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
      case tracked: TrackingJavaFileObject => tracked.path
      case tracked: TrackingPlainFileObject => tracked.path
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
      previous: Snapshot,
      currentStamps: Map[os.Path, SourceStamp],
      workDir: os.Path
  ): Set[os.Path] = {
    val classesDir = workDir / "classes"
    val previousStamps = previous.sourceStamps.iterator.map { case (path, stamp) =>
      workDir / os.RelPath(path) -> stamp
    }.toMap
    val changedSources = changedSourcesSince(previousStamps, currentStamps)
    val anySourceChanged = sourceSetChanged(previousStamps, currentStamps)
    val removedSources = previousStamps.keySet -- currentStamps.keySet

    val staleIsolating =
      changedSources.iterator
        .flatMap(source => previous.isolatingProducts.get(source.relativeTo(workDir).toString).iterator)
        .flatten
        .map(classesDir / os.RelPath(_))
        .toSet

    val staleAggregating =
      previous.aggregatingProducts.iterator.collect {
        case (product, owners)
            if owners.iterator.map(workDir / os.RelPath(_)).exists(changedSources.contains) ||
              owners.iterator.map(workDir / os.RelPath(_)).exists(removedSources.contains) =>
          classesDir / os.RelPath(product)
      }.toSet

    val staleUnknown =
      if (anySourceChanged) previous.unknownProducts.iterator.map(classesDir / os.RelPath(_)).toSet
      else Set.empty[os.Path]

    staleIsolating ++ staleAggregating ++ staleUnknown
  }

  private def forcedChangedSourcesFor(
      previous: Snapshot,
      currentSources: Set[os.Path],
      removedSources: Set[os.Path],
      workDir: os.Path
  ): Set[os.Path] = {
    val _ = removedSources
    previous.aggregatingProducts.valuesIterator
      .flatten
      .map(workDir / os.RelPath(_))
      .filter(currentSources.contains)
      .toSet
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
    private val isolatingProducts = mutable.LinkedHashMap.empty[os.Path, mutable.LinkedHashSet[os.Path]]
    private val aggregatingProducts = mutable.LinkedHashMap.empty[os.Path, Set[os.Path]]
    private val unknownProducts = mutable.LinkedHashSet.empty[os.Path]
    private val touchedProducts = mutable.LinkedHashSet.empty[os.Path]

    def recordExplicitOwnership(fileObject: FileObject, owners: Set[os.Path]): Unit =
      for (outputPath <- fileObjectPath(fileObject)) {
        generatedOwners(outputPath) =
          if (owners.nonEmpty) Provenance.Known(owners) else Provenance.Unknown
      }

    def ownersForElements(
        elements: Iterable[Element],
        trees: Option[com.sun.source.util.Trees],
        elementUtils: Elements
    ): Set[os.Path] =
      elements.iterator.flatMap(ownerForElement(_, trees, elementUtils)).toSet

    def recordGenerated(fileObject: FileObject, sibling: Option[FileObject]): Unit = {
      for (outputPath <- fileObjectPath(fileObject)) {
        val siblingPath = sibling.flatMap(fileObjectPath)
        val provenance = generatedOwners.getOrElse(outputPath, ownerFor(siblingPath, outputPath))
        generatedOwners(outputPath) = provenance
        val outputOsPath = os.Path(outputPath)
        if (outputOsPath.startsWith(classesDir)) {
          touchedProducts += outputOsPath
          provenance match {
            case Provenance.Known(owners) if trackingMode == TrackingMode.Isolating && owners.size == 1 =>
              val source = owners.head
              val products = isolatingProducts.getOrElseUpdate(source, mutable.LinkedHashSet.empty)
              products += outputOsPath
            case Provenance.Known(owners) if owners.nonEmpty =>
              aggregatingProducts(outputOsPath) = owners
            case _ =>
              unknownProducts += outputOsPath
          }
        }
      }
    }

    def cleanupFailedCompile(): Unit =
      touchedProducts.foreach(os.remove.all(_))

    def snapshot: TrackerSnapshot =
      TrackerSnapshot(
        isolatingProducts = isolatingProducts.view.mapValues(_.toSet).toMap,
        aggregatingProducts = aggregatingProducts.toMap,
        unknownProducts = unknownProducts.toSet
      )

    private def ownerFor(siblingPath: Option[Path], outputPath: Path): Provenance =
      trackingMode match {
        case TrackingMode.Aggregating =>
          siblingPath
            .flatMap(path => sourceOwners.get(path).orElse(generatedOwners.get(path)))
            .getOrElse(Provenance.Unknown)
        case TrackingMode.Isolating =>
          siblingPath
            .flatMap(path => sourceOwners.get(path).orElse(generatedOwners.get(path)))
            .orElse(inferOwnerFromOutputPath(outputPath))
            .getOrElse(Provenance.Unknown)
      }

    private def inferOwnerFromOutputPath(outputPath: Path): Option[Provenance.Known] = {
      val normalized = outputPath.toAbsolutePath.normalize()
      val outputString = normalized.toString
      val fileName = normalized.getFileName.toString
      val stem = {
        val withoutExt = fileName.reverse.dropWhile(_ != '.').drop(1).reverse
        val base = if (withoutExt.nonEmpty) withoutExt else fileName
        base.split('.').last
      }
      val candidates = sourceMetadata.filter { source =>
        outputString.contains(source.packagePath) && source.matchesGeneratedStem(stem)
      }
      Option.when(candidates.size == 1)(Provenance.Known(Set(candidates.head.path)))
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

  case class TrackerSnapshot(
      isolatingProducts: Map[os.Path, Set[os.Path]],
      aggregatingProducts: Map[os.Path, Set[os.Path]],
      unknownProducts: Set[os.Path]
  )

  case class SourceMetadata(path: os.Path, simpleName: String, packageName: String, packagePath: String) {
    def matchesGeneratedStem(stem: String): Boolean =
      stem == simpleName ||
        stem.startsWith(simpleName) ||
        stem.endsWith(simpleName)

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
      val packagePath =
        if (packageSegments.isEmpty) File.separator
        else packageSegments.mkString(File.separator, File.separator, File.separator)
      val packageName = packageSegments.mkString(".")
      SourceMetadata(path, simpleName, packageName, packagePath)
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
