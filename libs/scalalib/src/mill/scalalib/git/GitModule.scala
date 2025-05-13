package mill.scalalib.git

import mill.define.*

@mill.api.experimental
trait GitModule extends WithGitWorker {

  /**
   * Returns the list of files that changed between 2 revisions.
   *  - Deleted files are excluded.
   *  - The new location is returned for renamed/copied files.
   *
   *  Usage:
   *  {{{
   *  ratchet HEAD        // compare working tree against HEAD
   *  ratchet             // same as above
   *  ratchet HEAD^ HEAD  // compare HEAD against the commit before it
   *  ratchet origin/main // compare working tree against origin/main HEAD
   *  }}}
   * @param oldRev revision to compare against
   * @param newRev revision, or working tree when empty, to compare
   * @see [[https://javadoc.io/doc/org.eclipse.jgit/org.eclipse.jgit/latest/org.eclipse.jgit/org/eclipse/jgit/lib/Repository.html#resolve(java.lang.String)]]
   */
  def ratchet(
      @mainargs.arg(positional = true)
      oldRev: String = "HEAD",
      @mainargs.arg(positional = true)
      newRev: Option[String] = None
  ) = Task.Command {
    gitWorker().worker().ratchet(oldRev, newRev)
  }
}
@mill.api.experimental
object GitModule extends ExternalModule, GitModule {
  lazy val millDiscover = Discover[this.type]
}
