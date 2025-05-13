package mill.scalalib.git

import mill.define.{Module, ModuleRef}

@mill.api.experimental
trait WithGitWorker extends Module {
  def gitWorker: ModuleRef[GitWorkerModule] = ModuleRef(GitWorkerModule)
}
