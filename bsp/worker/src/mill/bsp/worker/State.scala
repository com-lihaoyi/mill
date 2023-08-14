package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.JavaModuleUtils
import mill.define.Module
import mill.eval.Evaluator

private class State(evaluators: Seq[Evaluator], debug: String => Unit) {
  lazy val bspModulesById: Map[BuildTargetIdentifier, (BspModule, Evaluator)] = {
    val modules: Seq[(Module, Seq[Module], Evaluator)] = evaluators
      .map(ev => (ev.rootModule, JavaModuleUtils.transitiveModules(ev.rootModule), ev))

    val map = modules
      .flatMap { case (rootModule, otherModules, eval) =>
        (Seq(rootModule) ++ otherModules).collect {
          case m: BspModule =>
            val uri = Utils.sanitizeUri(
              rootModule.millSourcePath / m.millModuleSegments.parts
            )

            (new BuildTargetIdentifier(uri), (m, eval))
        }
      }
      .toMap
    debug(s"BspModules: ${map.mapValues(_._1.bspDisplayName).toMap}")

    map
  }

  lazy val rootModules = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModule, BuildTargetIdentifier] =
    bspModulesById.mapValues(_._1).map(_.swap).toMap
}
