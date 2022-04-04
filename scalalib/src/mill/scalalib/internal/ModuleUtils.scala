package mill.scalalib.internal

import mill.define.{Module, Segments}
import mill.scalalib.JavaModule

/**
 * Compute all transitive modules from module children and via moduleDeps
 */
@mill.api.internal
object ModuleUtils {
  def transitiveModules(module: Module, accept: Module => Boolean = _ => true): Seq[Module] = {
    def loop(mod: Module, found: Seq[Module]): Seq[Module] = {
      if (!accept(mod) || found.contains(mod))
        found
      else {
        val subMods = mod.millModuleDirectChildren ++ (mod match {
          case jm: JavaModule => jm.moduleDeps ++ jm.compileModuleDeps
          case other => Seq.empty
        })
        subMods.foldLeft(found ++ Seq(mod)) { (all, mod) => loop(mod, all) }
      }
    }

    loop(module, Seq.empty)
  }
  def moduleDisplayName(module: Module): String = {
    (module.millModuleShared.value.getOrElse(Segments()) ++ module.millModuleSegments).render
  }
}
