package mill.scalalib.spotless

import com.diffplug.spotless.{java as _, scala as _, *}
import mill.define.{PathRef, TaskCtx}
import mill.scalalib.CoursierModule

import java.nio.charset.Charset
import java.nio.file.FileSystems
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class SpotlessWorkerImpl(formats: Seq[Format], provisioner: Provisioner)
    extends SpotlessWorker {

  def this(formats: Seq[Format], resolver: CoursierModule.Resolver, ctx: TaskCtx) =
    this(formats, toProvisioner(resolver)(using ctx))

  private val cleanCache = mutable.Map.empty[os.Path, Int]
  private def isClean(ref: PathRef) = cleanCache.get(ref.path).contains(ref.sig)
  private def onClean(ref: PathRef) = cleanCache.update(ref.path, ref.sig)

  def format(files: Seq[PathRef], check: Boolean)(using ctx: TaskCtx) = {
    val suspects0 = files.filterNot(isClean)
    if (suspects0.nonEmpty) {
      // https://github.com/diffplug/spotless/blob/main/PADDEDCELL.md#a-misbehaving-step
      var numDiverges = 0
      var numLints = 0
      var numDirty = 0
      var numCleaned = 0
      for format <- formats do
        import format.*
        val isMatch =
          val include = includes.map(FileSystems.getDefault.getPathMatcher)
          val exclude = excludes.map(FileSystems.getDefault.getPathMatcher)
          (ref: PathRef) =>
            include.exists(_.matches(ref.path.toNIO)) &&
              !exclude.exists(_.matches(ref.path.toNIO))
        val suspects = suspects0.filter(isMatch)
        if (suspects.nonEmpty) {
          val policy = LineEnding.valueOf(lineEnding)
            .createPolicy(ctx.workspace.toIO, () => suspects.map(_.path.toIO).asJava)
          val charset = Charset.forName(encoding)
          val formatter = Formatter.builder()
            .lineEndingsPolicy(policy)
            .encoding(charset)
            .steps(steps.map(ToFormatterStep(charset, provisioner)).asJava)
            .build
          val lintSuppressions = suppressions.map(toLintSuppression).asJava
          val fileTypes = suspects.iterator
            .map: ref =>
              val ext = ref.path.ext
              if ext.isEmpty then ref.path.baseName else ext
            .distinct
            .toSeq
            .sorted
            .mkString(",")
          ctx.log.info(s"checking format in ${suspects.length} $fileTypes files")

          for ref <- suspects do
            val path = ref.path
            val file = path.toIO
            val rel = path.relativeTo(ctx.workspace)
            ctx.log.debug(s"checking format in $rel")
            LintState.of(formatter, file)
              .withRemovedSuppressions(formatter, rel.toString(), lintSuppressions) match
              case ls if ls.getDirtyState.didNotConverge =>
                ctx.log.warn(s"failed to converge $rel")
                numDiverges += 1
              case ls if ls.isHasLints =>
                ctx.log.info(ls.asStringOneLine(file, formatter))
                numLints += 1
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
          end for
        }

      if (numCleaned > 0) ctx.log.info(s"formatted $numCleaned files")
      if (numDirty > 0) ctx.log.info(s"format errors in $numDirty files")
      if (numLints > 0) ctx.log.warn(s"lint errors in $numLints files must be fixed/suppressed")
      if (numDiverges > 0) ctx.log.warn(s"failed to converge $numDiverges files")

      val prefix = if check then "format check" else "format"
      val numFailed = numDirty + numLints + numDiverges
      if (numFailed > 0) ctx.fail(s"$prefix failed for $numFailed files")
    } else ctx.log.info("everything is already formatted")
  }

  def provision(using ctx: TaskCtx) = {
    // Spotless defers artifact resolution until format
    // so we build and apply all steps on an empty file
    ctx.log.debug("provisioning artifacts needed by spotless")
    val artifacts = Seq.newBuilder[PathRef]
    val provisioner0: Provisioner = (withTransitives, mavenCoordinates) => {
      val files = provisioner.provisionWithTransitives(withTransitives, mavenCoordinates)
      artifacts ++= files.iterator().asScala.map(file => PathRef(os.Path(file)))
      files
    }
    val charset = Charset.defaultCharset()
    val formatter = Formatter.builder()
      .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
      .encoding(charset)
      .steps(formats.iterator
        .flatMap(_.steps)
        .map(ToFormatterStep(charset, provisioner0))
        .toSeq.asJava)
      .build()
    LintState.of(formatter, os.temp().toIO)
    artifacts.result()
  }
}
