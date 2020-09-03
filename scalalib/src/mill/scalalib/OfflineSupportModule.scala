package mill.scalalib

import mill.T
import mill.define.Command

trait OfflineSupportModule extends mill.Module {

  /**
   * Prepare the module for working offline. This should typically fetch (missing) resources like ivy dependencies.
   */
  def prepareOffline(): Command[Unit] = T.command {
    // nothing to do
  }

}
