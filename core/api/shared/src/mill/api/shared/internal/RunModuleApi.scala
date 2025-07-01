package mill.api.shared.internal

import mill.api.shared.internal.bsp.BspRunModuleApi

trait RunModuleApi extends ModuleApi {

  /**
   * Internal access to some BSP helper tasks
   */
  private[mill] def bspRunModule: () => BspRunModuleApi
}
