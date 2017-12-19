package mill.util

import ammonite.ops._

import org.eclipse.jgit.api.{Git => JGit}

object Git {
  def gitClone(repoUrl: String, commish: String, path: Path): Unit = {
    val git = JGit.cloneRepository().setURI(repoUrl).setDirectory(path.toIO).call()
    val clonedRepo = ls(path)
  }

}
