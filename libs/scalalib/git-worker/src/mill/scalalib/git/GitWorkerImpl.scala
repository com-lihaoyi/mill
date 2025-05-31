package mill.scalalib.git

import mill.define.PathRef
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, FileTreeIterator}

import java.io.OutputStream
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * @see [[https://github.com/centic9/jgit-cookbook]]
 */
class GitWorkerImpl(workspace: os.Path) extends GitWorker {

  val git = Git.open(workspace.toIO)

  def close() = {
    /* TODO fix classpath issue that manifests as
        java.lang.NoClassDefFoundError: org/eclipse/jgit/lib/RepositoryCache
          org.eclipse.jgit.lib.Repository.close(Repository.java:939)
          org.eclipse.jgit.api.Git.close(Git.java:136)
          mill.scalalib.git.GitWorkerImpl.close(GitWorkerImpl.scala:28)
     */
    // git.close()
    // Git.shutdown()
  }

  def ratchet(oldRev: String, newRev: Option[String]): Seq[PathRef] = {
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
          PathRef(workspace / os.SubPath(entry.getNewPath))
      .toSeq
  }

  def commitTree(rev: String) = {
    val repository = git.getRepository
    Using.resource(RevWalk(repository)) { walk =>
      val revCommit = walk.parseCommit(repository.resolve(rev))
      val revTree = walk.parseTree(revCommit.getTree.getId)
      Using.resource(repository.newObjectReader()) { reader =>
        CanonicalTreeParser(null, reader, revTree.getId)
      }
    }
  }

  def workingDirTree = FileTreeIterator(git.getRepository)
}
