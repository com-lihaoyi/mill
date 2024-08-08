package mill.scalalib

import mainargs.Flag
import mill.{task, T}
import mill.define.Command

trait OfflineSupportModule extends mill.Module {

  /**
   * Prepare the module for working offline. This should typically fetch (missing) resources like ivy dependencies.
   * @param all If `true`, it also fetches resources not always needed.
   */
  def prepareOffline(all: Flag): Command[Unit] = task.command {
    // nothing to do
  }

}
