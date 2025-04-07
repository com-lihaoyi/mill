package mill.scalalib.internal

import mill.define.Module
import mill.runner.api.ModuleApi
import mill.scalalib.JavaModule

@mill.api.internal
object JavaModuleUtils {

  /**
   * Compute all transitive modules from module children and via moduleDeps + compileModuleDeps
   */
  def transitiveModules(module: ModuleApi): Seq[ModuleApi] = {
    Seq(module) ++ module.moduleDirectChildren.flatMap(transitiveModules)
  }

}
