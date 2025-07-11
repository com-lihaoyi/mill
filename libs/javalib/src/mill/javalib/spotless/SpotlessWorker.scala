package mill.javalib.spotless

import mill.api.{PathRef, TaskCtx}

@mill.api.daemon.internal.internal
trait SpotlessWorker extends AutoCloseable {

  /**
   * Checks/fixes formatting in module files.
   * @param check If set, an error is raised on format errors. Otherwise, formatting is fixed.
   */
  def format(check: Boolean)(using TaskCtx.Log): Unit

  /**
   * Resolves and returns artifacts required for formatting.
   */
  def provision(using TaskCtx.Log): Seq[PathRef]

  /**
   * Checks/fixes formatting in `files` that differ in 2 Git trees.
   * @param check If set, an error is raised on format errors. Otherwise, formatting is fixed.
   * @param from Revision to compare against.
   * @param to Revision to compare. When empty,
   *           - index tree is used, if `staged` is set
   *           - working tree is used, otherwise
   */
  def ratchet(check: Boolean, staged: Boolean, from: String, to: Option[String])(using
      TaskCtx.Log & TaskCtx.Workspace
  ): Unit
}
