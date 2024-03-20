package mill.scalalib

import mill.T
import mill.define.Module
import mill.api.JsonFormatters.pathReadWrite
import mill.api.PathRef

trait RunModule extends Module {

  /**
   * Any command-line parameters you want to pass to the forked JVM.
   */
  def forkArgs: T[Seq[String]] = T { Seq.empty[String] }

  /**
   * Any environment variables you want to pass to the forked JVM.
   */
  def forkEnv: T[Map[String, String]] = T.input { T.env }

  def forkWorkingDir: T[os.Path] = T { T.workspace }

  /**
   * All classfiles and resources including upstream modules and dependencies
   * necessary to run this module's code.
   */
  def runClasspath: T[Seq[PathRef]] = T { Seq.empty[PathRef] }

  /**
   * Control whether `run*`-targets should use an args file to pass command line args, if possible.
   */
  def runUseArgsFile: T[Boolean] = T { scala.util.Properties.isWin }

//  def zincWorker: ModuleRef[ZincWorkerModule] = ModuleRef(mill.scalalib.ZincWorkerModule)

}
