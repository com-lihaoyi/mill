package mill.internal

import mill.define.Module
import mill.scalalib.JavaModule

@mill.api.internal
private[mill] object ModuleUtils {
  // Compute all transitive modules from build children and via moduleDeps
  def transitiveModules(module: Module, toSkip: Module => Boolean = _ => false): Seq[Module] = {
    def loop(mod: Module, found: Seq[Module]): Seq[Module] = {
      if (toSkip(mod) || found.contains(mod))
        found
      else {
        val subMods = mod.millModuleDirectChildren ++ (mod match {
          case jm: JavaModule => jm.moduleDeps
          case other => Seq.empty
        })
        subMods.foldLeft(found ++ Seq(mod)) { (all, mod) => loop(mod, all) }
      }
    }

    loop(module, Seq.empty)
  }
}
