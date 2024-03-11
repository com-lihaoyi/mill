package mill.scalalib

import mill.define.{Module, ModuleRef}

/**
 * Common trait for modules that use either a custom or a globally shared [[ZincWorkerModule]].
 */
trait ZincWorkerAware extends Module {
  def zincWorker: ModuleRef[ZincWorkerModule] = ModuleRef(ZincWorkerModule)
}
