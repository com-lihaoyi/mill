package mill.scalalib.git

import mill.define.{PathRef, TaskCtx}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, FileTreeIterator}

import java.io.OutputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * @see [[https://github.com/centic9/jgit-cookbook]]
 */
class GitWorkerImpl(git: Git) extends GitWorker {

  def this(gitDir: os.Path) = this(Git(FileRepositoryBuilder().findGitDir(gitDir.toIO).build()))

  def ratchet(oldRev: String, newRev: Option[String])(using ctx: TaskCtx): Seq[PathRef] = {
    git.diff()
      .setOldTree(commitTree(oldRev))
      .setNewTree(newRev.fold(workingDirTree)(commitTree))
      .setOutputStream(OutputStream.nullOutputStream())
      .setPathFilter(TreeFilter.ANY_DIFF)
      .call()
      .iterator()
      .asScala
      .collect:
        case entry if entry.getChangeType != DiffEntry.ChangeType.DELETE =>
          PathRef(ctx.workspace / os.SubPath(entry.getNewPath))
      .toSeq
  }

  def commitTree(rev: String) = {
    val repository = git.getRepository
    Using.resource(RevWalk(repository)) { walk =>
      val revCommit = walk.parseCommit(repository.resolve(rev))
      val revTree = walk.parseTree(revCommit.getTree.getId)
      Using.resource(repository.newObjectReader())(CanonicalTreeParser(
        Array.emptyByteArray,
        _,
        revTree.getId
      ))
    }
  }

  def workingDirTree = FileTreeIterator(git.getRepository)
}
