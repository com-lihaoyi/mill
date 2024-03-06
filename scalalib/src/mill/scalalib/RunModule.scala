package mill.scalalib

import mill.T
import mill.define.ModuleRef
import mill.api.JsonFormatters.pathReadWrite

trait RunModule extends RunBaseModule {
  def forkArgs: T[Seq[String]] = T { Seq.empty[String] }
  def forkEnv: T[Map[String, String]] = T.input { T.env }
  def forkWorkingDir: T[os.Path] = T { T.workspace }
  def runUseArgsFile: T[Boolean] = T { scala.util.Properties.isWin }
  def zincWorker: ModuleRef[ZincWorkerModule] = ModuleRef(mill.scalalib.ZincWorkerModule)

}
