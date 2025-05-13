package mill.scalalib.git

import mill.define.*

@mill.api.experimental
object GitModule extends ExternalModule {

  lazy val millDiscover = Discover[this.type]

  /**
   * File changes (excluding deletions) obtained by comparing Git trees.
   * @param oldRev old revision, defaults to `HEAD`
   * @param newRev new revision
   *               - when empty, the comparison is performed against the working directory tree
   * @see [[https://javadoc.io/doc/org.eclipse.jgit/org.eclipse.jgit/latest/org.eclipse.jgit/org/eclipse/jgit/lib/Repository.html#resolve(java.lang.String)]]
   */
  def ratchet(
      @mainargs.arg(positional = true)
      oldRev: String = "HEAD",
      @mainargs.arg(positional = true)
      newRev: Option[String] = None
  ): Task.Command[Seq[PathRef]] = Task.Command {
    gitWorker().worker().ratchet(oldRev, newRev)
  }

  def gitWorker: ModuleRef[GitWorkerModule] = ModuleRef(GitWorkerModule)
}
