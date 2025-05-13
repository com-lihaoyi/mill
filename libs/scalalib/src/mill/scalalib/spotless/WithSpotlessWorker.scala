package mill.scalalib.spotless

import mill.define.{Module, ModuleRef}

trait WithSpotlessWorker extends Module {
  def spotlessWorker: ModuleRef[SpotlessWorkerModule] = ModuleRef(SpotlessWorkerModule)
}
