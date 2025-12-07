package mill.javalib

import mainargs.Flag
import mill.api.{ModuleRef, PathRef, Task}

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
trait WithJvmWorkerModule extends JavaHomeModule {
  def jvmWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorkerModule)

//  override def prepareOffline(all: Flag): Task.Command[Seq[PathRef]] = Task.Command {
//    (super.prepareOffline(all)() ++ jvmWorker().prepareOffline(all)()).distinct
//  }
}
