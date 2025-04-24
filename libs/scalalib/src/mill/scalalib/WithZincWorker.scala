package mill.scalalib

import mill.define.{Module, ModuleRef}

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
trait WithJvmWorker extends Module {
  def jvmWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorkerModule)
}
