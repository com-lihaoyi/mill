package mill.scalalib.spotless

import com.diffplug.spotless.{java as _, scala as _, *}
import mill.define.{PathRef, TaskCtx}
import mill.scalalib.CoursierModule
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, FileTreeIterator}

import java.nio.charset.Charset
import java.nio.file.FileSystems
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * @see [[https://github.com/centic9/jgit-cookbook]]
 */
class SpotlessWorkerImpl(formats: Seq[Format], provisioner: Provisioner)
    extends SpotlessWorker {

  def this(formats: Seq[Format], resolver: CoursierModule.Resolver, ctx: TaskCtx) =
    this(formats, toProvisioner(resolver)(using ctx))

  private val cleanCache = mutable.Map.empty[os.Path, Int]
  private def isClean(ref: PathRef) = cleanCache.get(ref.path).contains(ref.sig)
  private def onClean(ref: PathRef) = cleanCache.update(ref.path, ref.sig)

  def close() = {
    Git.shutdown()
  }

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
                ctx.log.info(ls.asStringOneLine(file, formatter)) // this logs one line per lint
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

      val numFailed = numDirty + numLints + numDiverges
      if (numFailed > 0) {
        val prefix = if check then "format check" else "format"
        ctx.fail(s"$prefix failed for $numFailed files")
      }
    } else ctx.log.info("everything is already formatted")
  }

  def provision(using ctx: TaskCtx) = {
    // Spotless defers artifact resolution until format
    // so we build and apply all steps on an empty file
    ctx.log.debug("provisioning artifacts for spotless")
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
        .toSeq
        .asJava)
      .build()
    LintState.of(formatter, os.temp().toIO)
    artifacts.result()
  }

  def ratchet(fromRev: String, toRev: Option[String], check: Boolean)(using ctx: TaskCtx) = {
    val files = Using.resource(Git.open(ctx.workspace.toIO)) { git =>
      def commitTree(rev: String) = {
        val id = git.getRepository.resolve(rev)
        require(null != id, s"commit $id not found")
        Using.resource(RevWalk(git.getRepository)) { revWalk =>
          val revCommit = revWalk.parseCommit(id)
          val revTree = revWalk.parseTree(revCommit.getTree.getId)
          Using.resource(git.getRepository.newObjectReader()) { reader =>
            CanonicalTreeParser(null, reader, revTree.getId)
          }
        }
      }
      def workingTree = FileTreeIterator(git.getRepository)

      val oldTree = commitTree(fromRev)
      val newTree = toRev.fold(workingTree)(commitTree)
      val treeFilter = TreeFilter.ANY_DIFF

      git.diff()
        .setOldTree(oldTree)
        .setNewTree(newTree)
        .setPathFilter(treeFilter)
        .call()
        .iterator()
        .asScala
        .collect:
          case entry if DiffEntry.ChangeType.DELETE != entry.getChangeType =>
            PathRef(ctx.workspace / os.SubPath(entry.getNewPath))
        .toSeq
    }
    if (files.isEmpty) ctx.log.info("ratchet found no changes")
    else {
      ctx.log.info(s"ratchet found changes in ${files.length} files")
      format(files, check)
    }
  }
}
