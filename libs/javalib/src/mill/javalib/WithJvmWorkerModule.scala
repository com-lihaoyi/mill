package mill.javalib

import mill.api.ModuleRef

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
trait WithJvmWorkerModule extends JavaHomeModule {
  def jvmWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorkerModule)

  // TODO: add once we can break bin-compat
  //  override def prepareOffline(all: Flag): Task.Command[Seq[PathRef]] = Task.Command {
  //    (super.prepareOffline(all)() ++ jvmWorker().prepareOffline(all)()).distinct
  //  }
}
