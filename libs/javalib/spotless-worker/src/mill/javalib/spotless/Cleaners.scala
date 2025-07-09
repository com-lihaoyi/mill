package mill.javalib.spotless

import com.diffplug.spotless.{java as _, scala as _, *}
import mill.api.{PathRef, TaskCtx}
import mill.javalib.spotless._
import java.nio.charset.Charset
import java.nio.file.FileSystems
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class Cleaners(
    moduleDir: os.Path,
    globalExcludes: Seq[String],
    formats: Seq[Format],
    provisioner: Provisioner
) {
  val cache = mutable.Map.empty[os.Path, Int]
  def isClean(ref: PathRef) = cache.get(ref.path).contains(ref.sig)
  def onClean(ref: PathRef) = cache.update(ref.path, ref.sig)

  var numDiverged = 0
  var numLints = 0
  var numDirty = 0
  var numCleaned = 0

  val cleaners =
    for format <- formats yield
      def matches(patterns: Seq[String]) =
        val matchers = patterns.map(FileSystems.getDefault.getPathMatcher)
        (path: os.Path) =>
          val nio = path.relativeTo(moduleDir).toNIO
          matchers.exists(_.matches(nio))
      import format.*
      val charset = Charset.forName(encoding)
      Cleaner(
        includes.mkString(" "),
        matches(includes),
        matches(excludes ++ globalExcludes),
        LineEnding.valueOf(lineEnding),
        charset,
        steps.map(ToFormatterStep(charset, provisioner)).asJava,
        suppressions.map(toLintSuppression).asJava
      )

  class Cleaner(
      id: String,
      include: os.Path => Boolean,
      exclude: os.Path => Boolean,
      lineEnding: LineEnding,
      encoding: Charset,
      steps: java.util.List[FormatterStep],
      suppressions: java.util.List[LintSuppression]
  ) {
    def isTarget(ref: PathRef) = !exclude(ref.path) && include(ref.path)

    def format(check: Boolean)(using ctx: TaskCtx.Log): Unit = {
      val targets = os.walk.stream(moduleDir, skip = exclude)
        .filter(os.isFile)
        .filter(include)
        .map(PathRef(_))
        .toSeq
      if (targets.isEmpty) ctx.log.warn(s"no files to format for $id")
      else format(check, targets)
    }

    def format(check: Boolean, targets: Seq[PathRef])(using ctx: TaskCtx.Log): Unit = {
      if (targets.isEmpty) return

      val fid = targets.iterator
        .map: ref =>
          val ext = ref.path.ext
          if ext.isEmpty then ref.path.baseName else ext
        .distinct
        .toSeq
        .sorted
        .mkString(",")
      val files = targets.filterNot(isClean)

      if (files.isEmpty) {
        ctx.log.info(s"${targets.length} $fid files are already formatted")
        return
      }

      val policy = lineEnding.createPolicy(moduleDir.toIO, () => files.map(_.path.toIO).asJava)
      val formatter =
        Formatter.builder().lineEndingsPolicy(policy).encoding(encoding).steps(steps).build

      ctx.log.info(s"checking format in ${files.length} $fid files")
      for ref <- files do
        val path = ref.path
        val file = path.toIO
        val rel = path.relativeTo(moduleDir)
        ctx.log.debug(s"checking format in $rel")
        LintState.of(formatter, file)
          .withRemovedSuppressions(formatter, rel.toString(), suppressions) match
          case ls if ls.getDirtyState.didNotConverge =>
            // https://github.com/diffplug/spotless/blob/main/PADDEDCELL.md#a-misbehaving-step
            ctx.log.error(s"failed to converge $rel")
            numDiverged += 1
          case ls if ls.isHasLints =>
            ctx.log.error(ls.asStringDetailed(file, formatter))
            numLints += 1
          case ls if ls.getDirtyState.isClean =>
            onClean(ref)
          case _ if check =>
            ctx.log.error(s"format errors in $rel")
            numDirty += 1
          case ls =>
            ctx.log.info(s"formatting $rel")
            ls.getDirtyState.writeCanonicalTo(file)
            onClean(PathRef(path))
            numCleaned += 1
        end match
      end for
    }
  }

  def format(check: Boolean)(using ctx: TaskCtx.Log) = {
    for cleaner <- cleaners
    do cleaner.format(check)
    finish(check)
  }

  def format(check: Boolean, files: Seq[PathRef])(using ctx: TaskCtx.Log) = {
    for
      cleaner <- cleaners
      targets = files.filter(cleaner.isTarget)
    do cleaner.format(check, targets)
    finish(check)
  }

  def finish(check: Boolean)(using ctx: TaskCtx.Log) = {
    if (numCleaned > 0) ctx.log.info(s"formatted $numCleaned files")
    if (numDirty > 0) ctx.log.error(s"format errors in $numDirty files")
    if (numLints > 0) ctx.log.error(s"lint errors in $numLints files must be fixed/suppressed")
    if (numDiverged > 0) ctx.log.error(s"failed to converge $numDiverged files")

    val prefix = "format" + (if check then " check" else "")
    val numFailed = numDirty + numLints + numDiverged
    numDiverged = 0
    numLints = 0
    numDirty = 0
    numCleaned = 0

    if (numFailed > 0) sys.error(s"$prefix failed for $numFailed files")
    else ctx.log.info(s"$prefix completed")
  }

  def provision(using ctx: TaskCtx.Log) = {
    // Spotless defers artifact resolution until format
    // so we build and apply all steps on an empty file
    ctx.log.debug("provisioning artifacts for spotless")
    val artifacts = Seq.newBuilder[PathRef]
    val provisioner0: Provisioner = (withTransitives, mavenCoordinates) => {
      val files = provisioner.provisionWithTransitives(withTransitives, mavenCoordinates)
      artifacts ++= files.iterator().asScala.map(file => PathRef(os.Path(file), quick = true))
      files
    }
    val charset = Charset.defaultCharset()
    val formatter = Formatter.builder()
      .lineEndingsPolicy(LineEnding.UNIX.createPolicy())
      .encoding(charset)
      .steps(formats.iterator
        .flatMap(_.steps)
        .map(ToFormatterStep(charset, provisioner0))
        .toSeq
        .asJava)
      .build()
    LintState.of(formatter, os.temp().toIO)
    artifacts.result()
  }
}
