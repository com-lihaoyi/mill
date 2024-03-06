package mill.scalalib

import mill.T
import mill.api.PathRef
import mill.define.{Module, ModuleRef}

trait RunBaseModule extends Module {

  /**
   * Any command-line parameters you want to pass to the forked JVM.
   */
  def forkArgs: T[Seq[String]]

  /**
   * Any environment variables you want to pass to the forked JVM.
   */
  def forkEnv: T[Map[String, String]]

  def forkWorkingDir: T[os.Path]

  /**
   * All classfiles and resources including upstream modules and dependencies
   * necessary to run this module's code.
   */
  def runClasspath: T[Seq[PathRef]]

  /**
   * Control whether `run*`-targets should use an args file to pass command line args, if possible.
   */
  def runUseArgsFile: T[Boolean]
  def zincWorker: ModuleRef[ZincWorkerModule]
}
