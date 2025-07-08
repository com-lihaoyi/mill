package mill.javalib

import mill.T
import mill.api.{Module, ModuleRef, PathRef, Task}

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
trait WithJvmWorkerModule extends JavaHomeModule {
  def jvmWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorkerModule)

}
