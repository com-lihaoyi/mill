package mill.scalalib.spotless

import mill.define.{Module, ModuleRef}

@mill.api.experimental
trait WithSpotlessWorker extends Module {
  def spotlessWorker: ModuleRef[SpotlessWorkerModule] = ModuleRef(SpotlessWorkerModule)
}
