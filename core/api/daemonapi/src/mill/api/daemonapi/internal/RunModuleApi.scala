package mill.api.daemonapi.internal

import mill.api.daemonapi.internal.bsp.BspRunModuleApi

trait RunModuleApi extends ModuleApi {

  /**
   * Internal access to some BSP helper tasks
   */
  private[mill] def bspRunModule: () => BspRunModuleApi
}
