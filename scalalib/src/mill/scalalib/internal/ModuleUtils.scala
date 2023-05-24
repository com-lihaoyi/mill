package mill.scalalib.internal

import mill.define.{Module, Segments}

@mill.api.internal
object ModuleUtils {


  /**
   * Computes a display name for a module which is also disambiguates foreign modules.
   */
  def moduleDisplayName(module: Module): String = {
    (module.millModuleShared.value.getOrElse(Segments()) ++ module.millModuleSegments).render
  }
}
