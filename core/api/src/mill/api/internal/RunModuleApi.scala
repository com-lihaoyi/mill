package mill.api.internal

import mill.api.internal.bsp.BspRunModuleApi

trait RunModuleApi extends ModuleApi {

  /**
   * Internal access to some BSP helper tasks
   */
  private[mill] def bspRunModule: () => BspRunModuleApi
}
