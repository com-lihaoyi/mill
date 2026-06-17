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

  val repository = RepositoryBuilder().setFS(FS.DETECTED).findGitDir(root.wrapped.toFile).build()

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
    // Compare symlink-resolved real paths: in reproducible-build mode `root` (the module dir) and
    // `ctx.workspace` may carry different `os.Path` wrapped forms for the same directory (one
    // routed through a `../mill-workspace` forwarder alias, the other a real absolute path), so a
    // plain `==`/`relativeTo` would wrongly treat the root module as a sub-path and build a
    // PathFilter that matches nothing ("ratchet found no changes").
    val realRoot = PathRef.toResolvedOsPath(root)
    val realWorkspace = PathRef.toResolvedOsPath(ctx.workspace)
    val treeFilter =
      if realRoot == realWorkspace then TreeFilter.ANY_DIFF
      else
        AndTreeFilter.create(
          PathFilter.create(realRoot.relativeTo(realWorkspace).toString()),
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
