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

  private val cleanCache = mutable.Map.empty[os.Path, Int]
  private def isClean(ref: PathRef) = cleanCache.get(ref.path).contains(ref.sig)
  private def onClean(ref: PathRef) = cleanCache.update(ref.path, ref.sig)

  def format(targets: Seq[PathRef], check: Boolean)(using ctx: TaskCtx) = {
    val unclean = targets.filterNot(isClean)
    if (unclean.nonEmpty) {
      val numFailed = runners.iterator
        .map(_.format(unclean, check, onClean))
        .sum
      if (numFailed > 0) {
        val prefix = if check then "format check" else "format"
        ctx.fail(s"$prefix failed for $numFailed files")
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
      pathFilter: java.nio.file.Path => Boolean,
      lineEnding: LineEnding,
      encoding: Charset,
      steps: util.List[FormatterStep],
      suppressions: util.List[LintSuppression]
  ) {

    def format(targets: Seq[PathRef], check: Boolean, onClean: PathRef => Unit)(using
        ctx: TaskCtx.Log & TaskCtx.Workspace
    ): Int = {
      val files = targets.filter(ref => pathFilter(ref.path.toNIO))
      if files.isEmpty then 0
      else {
        val policy =
          lineEnding.createPolicy(ctx.workspace.toIO, () => files.map(_.path.toIO).asJava)
        val formatter = Formatter.builder()
          .lineEndingsPolicy(policy)
          .encoding(encoding)
          .steps(steps)
          .build
        val fileTypes = files.iterator
          .map: ref =>
            val ext = ref.path.ext
            if ext.isEmpty then ref.path.baseName else ext
          .distinct
          .toSeq
          .sorted
          .mkString(",")
        ctx.log.info(s"checking format in ${files.length} $fileTypes files")
        var numDiverging = 0
        var numHasLints = 0
        var numDirty = 0
        var numCleaned = 0

        for
          ref <- files
          path = ref.path
          file = path.toIO
          rel = path.relativeTo(ctx.workspace)
        do
          LintState.of(
            formatter,
            file
          ).withRemovedSuppressions(formatter, rel.toString(), suppressions) match
            case ls if ls.getDirtyState.didNotConverge =>
              ctx.log.warn(s"failed to converge $rel")
              numDiverging += 1
            case ls if ls.isHasLints =>
              ctx.log.warn(ls.asStringDetailed(file, formatter))
              numHasLints += 1
            case ls if ls.getDirtyState.isClean =>
              onClean(ref)
            case _ if check =>
              ctx.log.info(s"format errors in $rel")
              numDirty += 1
            case ls =>
              ctx.log.info(s"formatting $rel")
              ls.getDirtyState.writeCanonicalTo(file)
              onClean(PathRef(path))
              numCleaned += 1
          end match

        if (numCleaned > 0) ctx.log.info(s"formatted $numCleaned $fileTypes files")
        if (numDirty > 0) ctx.log.info(s"format errors in $numDirty $fileTypes files")
        if (numHasLints > 0) ctx.log.warn(
          s"lint errors in $numHasLints $fileTypes files must be fixed/suppressed"
        )
        // https://github.com/diffplug/spotless/blob/main/PADDEDCELL.md#a-misbehaving-step
        if (numDiverging > 0) ctx.log.warn(
          s"failed to converge $numDiverging $fileTypes files with " +
            steps.iterator.asScala.map(_.getName).mkString(",")
        )
        numDirty + numHasLints + numDiverging
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
        (path: java.nio.file.Path) =>
          includeMatchers.exists(_.matches(path)) && !excludeMatchers.exists(_.matches(path))
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
