package mill.scalalib.spotless

import com.diffplug.spotless.{java as _, scala as _, *}
import mill.define.{PathRef, TaskCtx}
import mill.scalalib.CoursierModule

import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.util
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class SpotlessWorkerImpl(runners: Seq[SpotlessWorkerImpl.Runner])
    extends SpotlessWorker {

  def this(formats: Seq[Format], provisioner: Provisioner) =
    this(formats.map(SpotlessWorkerImpl.Runner.create(_, provisioner)))

  def this(formats: Seq[Format], resolver: CoursierModule.Resolver, ctx: TaskCtx) =
    this(formats, toProvisioner(resolver)(using ctx))

  private val formattedSig = mutable.Map.empty[os.Path, Int]
  private val isFormatted = (ref: PathRef) => formattedSig.get(ref.path).contains(ref.sig)
  private val onFormat = (path: os.Path) => formattedSig.update(path, PathRef(path).sig)

  def format(files: Seq[PathRef], check: Boolean)(using ctx: TaskCtx) = {
    val toConsider = files.collect:
      case ref if !isFormatted(ref) => ref.path
    if (toConsider.nonEmpty) {
      val failedFileCount = runners.iterator
        .map(_.format(toConsider, check, onFormat))
        .sum
      if (failedFileCount > 0) {
        val prefix = if check then "format check" else "format"
        ctx.fail(s"$prefix failed for $failedFileCount files")
      }
    } else ctx.log.info("everything is already formatted")
  }
}
object SpotlessWorkerImpl {

  /**
   * Resolves and returns all artifacts required for applying `formats`.
   */
  def provision(
      formats: Seq[Format],
      resolver: CoursierModule.Resolver,
      ctx: TaskCtx
  ): Seq[PathRef] = {
    // Spotless defers artifact resolution until format
    // so we build and apply all steps on an empty file
    val artifacts = Seq.newBuilder[PathRef]
    val provisioner0 = toProvisioner(resolver)(using ctx)
    val provisioner: Provisioner = (withTransitives, mavenCoordinates) => {
      val files = provisioner0.provisionWithTransitives(withTransitives, mavenCoordinates)
      artifacts ++= files.iterator().asScala.map(file => PathRef(os.Path(file)))
      files
    }
    val charset = Charset.defaultCharset()
    val toFormatterStep = ToFormatterStep(charset, provisioner)
    val formatter = Formatter.builder()
      .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
      .encoding(charset)
      .steps(formats.iterator.flatMap(_.steps).map(toFormatterStep).toSeq.asJava)
      .build()
    LintState.of(formatter, os.temp().toIO)
    artifacts.result()
  }

  class Runner(
      pathFilter: os.Path => Boolean,
      lineEnding: LineEnding,
      encoding: Charset,
      steps: util.List[FormatterStep],
      suppressions: util.List[LintSuppression]
  ) {

    def format(files: Seq[os.Path], check: Boolean, onFormat: os.Path => Unit)(using
        ctx: TaskCtx.Log & TaskCtx.Workspace
    ): Int = {
      val targets = files.filter(pathFilter)
      if targets.isEmpty then 0
      else {
        val policy = lineEnding.createPolicy(ctx.workspace.toIO, () => targets.map(_.toIO).asJava)
        val formatter = Formatter.builder()
          .lineEndingsPolicy(policy)
          .encoding(encoding)
          .steps(steps)
          .build
        val fileTypes = targets.iterator
          .map: path =>
            val ext = path.ext
            if ext.isEmpty then path.baseName else ext
          .distinct
          .toSeq
          .sorted
          .mkString(",")
        var divergingFileCount = 0
        var lintsFileCount = 0
        var dirtyFileCount = 0
        var cleanedFileCount = 0
        ctx.log.info(s"checking format in ${targets.length} $fileTypes files")
        for (path <- targets) {
          val file = path.toIO
          val rel = path.relativeTo(ctx.workspace)
          val lintState = LintState.of(formatter, file)
            .withRemovedSuppressions(formatter, rel.toString(), suppressions)
          if (lintState.getDirtyState.didNotConverge()) {
            ctx.log.warn(s"failed to converge $rel")
            divergingFileCount += 1
          } else if (lintState.isHasLints) {
            ctx.log.warn(lintState.asStringDetailed(file, formatter))
            lintsFileCount += 1
          } else if (!lintState.getDirtyState.isClean) {
            if (check) {
              ctx.log.info(s"format errors in $rel")
              dirtyFileCount += 1
            } else {
              ctx.log.info(s"formatting $rel")
              lintState.getDirtyState.writeCanonicalTo(file)
              onFormat(path)
              cleanedFileCount += 1
            }
          }
          if (cleanedFileCount > 0)
            ctx.log.info(s"formatted $cleanedFileCount $fileTypes files")
          if (dirtyFileCount > 0)
            ctx.log.info(s"format errors in $dirtyFileCount $fileTypes files")
          if (lintsFileCount > 0)
            ctx.log.warn(
              s"lint errors in $lintsFileCount $fileTypes files must be fixed/suppressed"
            )
          if (divergingFileCount > 0)
            // https://github.com/diffplug/spotless/blob/main/PADDEDCELL.md#a-misbehaving-step
            ctx.log.warn(s"failed to converge $divergingFileCount $fileTypes files with steps: " +
              steps.iterator.asScala.map(_.getName).mkString(","))
        }
        dirtyFileCount + lintsFileCount + divergingFileCount
      }
    }
  }
  object Runner {

    def create(format: Format, provisioner: Provisioner): Runner = {
      import format.*
      val charset = Charset.forName(encoding)
      val toFormatterStep = ToFormatterStep(charset, provisioner)
      val pathFilter = {
        val includeMatchers = includes.map(FileSystems.getDefault.getPathMatcher)
        val excludeMatchers = excludes.map(FileSystems.getDefault.getPathMatcher)
        (path: os.Path) =>
          includeMatchers.exists(_.matches(path.toNIO)) &&
            !excludeMatchers.exists(_.matches(path.toNIO))
      }
      Runner(
        pathFilter,
        LineEnding.valueOf(lineEnding),
        charset,
        steps.map(toFormatterStep).asJava,
        suppressions.map(toLintSuppression).asJava
      )
    }
  }
}
