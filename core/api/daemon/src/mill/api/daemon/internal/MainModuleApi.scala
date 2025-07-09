package mill.api.daemon.internal

import mill.api.daemon.internal.bsp.BspMainModuleApi

trait MainModuleApi extends ModuleApi {

  /**
   * Internal access to some BSP helper tasks
   */
  private[mill] def bspMainModule: () => BspMainModuleApi

}
