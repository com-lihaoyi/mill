package mill.scalalib

import mill.T
import mill.api.PathRef
import mill.define.{Module, ModuleRef}

trait RunBaseModule extends Module {
  def forkArgs: T[Seq[String]]
  def forkEnv: T[Map[String, String]]
  def forkWorkingDir: T[os.Path]
  def runClasspath: T[Seq[PathRef]]
  def runUseArgsFile: T[Boolean]
  def zincWorker: ModuleRef[ZincWorkerModule]
}
