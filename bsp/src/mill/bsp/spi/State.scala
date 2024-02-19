package mill.bsp.spi

import mill.define.Module
import mill.eval.Evaluator
import mill.scalalib.bsp.{BspModule, BspUri}
import mill.scalalib.internal.JavaModuleUtils

class State private[bsp] (evaluators: Seq[Evaluator], debug: String => Unit) {

  /** Mapping of BSP target identifier to the Mill module and evaluator. */
  lazy val bspModulesById: Map[BspUri, (BspModule, Evaluator)] = {
    val modules: Seq[(Module, Seq[Module], Evaluator)] = evaluators
      .map(ev => (ev.rootModule, JavaModuleUtils.transitiveModules(ev.rootModule), ev))

    val map = modules
      .flatMap { case (rootModule, otherModules, eval) =>
        (Seq(rootModule) ++ otherModules).collect {
          case m: BspModule =>
            val uri = BspUri(
              rootModule.millSourcePath / m.millModuleSegments.parts
            )

            (uri, (m, eval))
        }
      }
      .toMap
    debug(s"BspModules: ${map.view.mapValues(_._1.bspDisplayName).toMap}")

    map
  }

  /** All root modules (at different meta-levels) of the project. */
  lazy val rootModules: Seq[mill.define.BaseModule] = evaluators.map(_.rootModule)

  /** Mapping of Mill Modules to BSP target identifiers. */
  lazy val bspIdByModule: Map[BspModule, BspUri] =
    bspModulesById.view.mapValues(_._1).map(_.swap).toMap
}
