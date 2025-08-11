package mill.api.daemon.internal

import mill.api.daemon.internal.bsp.BspRunModuleApi

trait RunModuleApi extends ModuleApi {

  /**
   * Internal access to some BSP helper tasks
   */
  private[mill] def bspRunModule: () => BspRunModuleApi
}
