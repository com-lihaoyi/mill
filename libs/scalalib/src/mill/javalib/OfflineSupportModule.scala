package mill.javalib

import mainargs.Flag
import mill.api.{PathRef, Task}

trait OfflineSupportModule extends mill.api.Module {

  /**
   * Prepare the module for working offline. This should typically fetch (missing) resources like Maven dependencies.
   *
   * @param all If `true`, it also fetches resources not always needed.
   */
  def prepareOffline(all: Flag): Task.Command[Seq[PathRef]] = {
    val check = Task.Anon {
      if (Task.offline) {
        Task.log.warn("Running prepareOffline while in --offline mode is likely not what you want")
      }
    }
    Task.Command {
      check()
      // nothing to do
      Seq.empty[PathRef]
    }
  }

}
