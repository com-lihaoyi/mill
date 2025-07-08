package mill.javalib.spotless

import mill.api.{PathRef, TaskCtx}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.{RepositoryBuilder, RepositoryCache}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.filter.{AndTreeFilter, PathFilter, TreeFilter}
import org.eclipse.jgit.treewalk.{CanonicalTreeParser, FileTreeIterator}
import org.eclipse.jgit.util.FS

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * @see [[https://github.com/centic9/jgit-cookbook]]
 */
class GitRatchet(root: os.Path) extends AutoCloseable {

  val repository = RepositoryBuilder().setFS(FS.DETECTED).findGitDir(root.toIO).build()

  // HACK:
  // Forcibly load classes referenced in close to avoid: java.lang.NoClassDefFoundError.
  RepositoryCache.register(repository)
  val git = Git.wrap(repository)

  def commitTree(rev: String) = {
    val id = repository.resolve(rev) match
      case null => sys.error(s"commit $rev not found")
      case id => id
    Using.resource(RevWalk(repository)) {
      revWalk =>
        val revCommit = revWalk.parseCommit(id)
        val revTree = revWalk.parseTree(revCommit.getTree.getId)
        Using.resource(repository.newObjectReader()) { reader =>
          CanonicalTreeParser(null, reader, revTree.getId)
        }
    }
  }

  def indexTree = DirCacheIterator(repository.readDirCache())

  def workingTree = FileTreeIterator(repository)

  def diff(staged: Boolean, from: String, to: Option[String])(using ctx: TaskCtx.Workspace) = {
    val oldTree = commitTree(from)
    val newTree = to.fold(if staged then indexTree else workingTree)(commitTree)
    val treeFilter =
      if root == ctx.workspace then TreeFilter.ANY_DIFF
      else
        AndTreeFilter.create(
          PathFilter.create(root.relativeTo(ctx.workspace).toString()),
          TreeFilter.ANY_DIFF
        )
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

  def close() = {
    repository.close()
    Git.shutdown()
  }
}
