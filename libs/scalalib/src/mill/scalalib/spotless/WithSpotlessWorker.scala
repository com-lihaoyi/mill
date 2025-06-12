package mill.scalalib.spotless

import mill.define.{Module, ModuleRef}

@mill.api.experimental // see notes in package object
trait WithSpotlessWorker extends Module {
  def spotlessWorker: ModuleRef[SpotlessWorkerModule] = ModuleRef(SpotlessWorkerModule)
}
