package mill.scalalib.spotless

import mill.api.Result
import mill.define.{PathRef, TaskCtx}

@mill.api.experimental // see notes in package object
trait SpotlessWorker extends AutoCloseable {

  /**
   * Checks/fixes formatting in `files`.
   * @param check If set, an error is raised on format errors. Otherwise, formatting is fixed.
   */
  def format(files: Seq[PathRef], check: Boolean)(using TaskCtx): Result[Unit]

  /**
   * Resolves and returns artifacts required by this worker.
   */
  def provision(using TaskCtx): Seq[PathRef]

  /**
   * Checks/fixes formatting in files that differ in 2 Git trees.
   * @param fromRev Revision to compare from.
   * @param toRev Revision to compare. When empty, the working tree is compared.
   * @param check If set, an error is raised on format errors. Otherwise, formatting is fixed.
   */
  def ratchet(fromRev: String, toRev: Option[String], check: Boolean)(using TaskCtx): Result[Unit]
}
