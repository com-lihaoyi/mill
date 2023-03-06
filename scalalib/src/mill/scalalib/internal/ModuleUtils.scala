package mill.scalalib.internal

import mill.define.{Module, Segments}
import mill.scalalib.JavaModule

@mill.api.internal
object ModuleUtils {

  /**
   * Compute all transitive modules from module children and via moduleDeps + compileModuleDeps
   */
  def transitiveModules(module: Module, accept: Module => Boolean = _ => true): Seq[Module] = {
    def loop(mod: Module, found: Seq[Module]): Seq[Module] = {
      if (!accept(mod) || found.contains(mod))
        found
      else {
        val subMods = mod.millModuleDirectChildren ++ (mod match {
          case jm: JavaModule => jm.moduleDepsChecked ++ jm.compileModuleDepsChecked
          case other => Seq.empty
        })
        subMods.foldLeft(found ++ Seq(mod)) { (all, mod) => loop(mod, all) }
      }
    }

    loop(module, Seq.empty)
  }

  /**
   * Computes a display name for a module which is also disambiguates foreign modules.
   */
  def moduleDisplayName(module: Module): String = {
    (module.millModuleShared.value.getOrElse(Segments()) ++ module.millModuleSegments).render
  }
}
