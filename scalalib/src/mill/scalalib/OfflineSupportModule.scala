package mill.scalalib

import mainargs.Flag
import mill.Task
import mill.define.Command

trait OfflineSupportModule extends mill.define.Module {

  /**
   * Prepare the module for working offline. This should typically fetch (missing) resources like ivy dependencies.
   * @param all If `true`, it also fetches resources not always needed.
   */
  def prepareOffline(all: Flag): Command[Unit] = {
    val check = Task.Anon {
      if (Task.offline) {
        Task.log.warn("Running prepareOffline while in --offline mode is likely not what you want")
      }
    }
    Task.Command {
      check()
      // nothing to do
    }
  }

}
