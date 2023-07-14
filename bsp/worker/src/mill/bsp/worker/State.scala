package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.runner.{MillBuildBootstrap, RootModuleFinder}
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.{JavaModuleUtils, ModuleUtils}
import mill.define.Module
import mill.util.ColorLogger

private class State(projectRoot: os.Path, baseLogger: ColorLogger, debug: String => Unit) {
  lazy val bspModulesById: Map[BuildTargetIdentifier, BspModule] = {
    val modules: Seq[(Module, Seq[Module])] = rootModules
      .map(rootModule => (rootModule, JavaModuleUtils.transitiveModules(rootModule)))

    val map = modules
      .flatMap { case (rootModule, otherModules) =>
        (Seq(rootModule) ++ otherModules).collect {
          case m: BspModule =>
            val uri = Utils.sanitizeUri(
              rootModule.millSourcePath / m.millModuleSegments.parts
            )

            (new BuildTargetIdentifier(uri), m)
        }
      }
      .toMap
    debug(s"BspModules: ${map.view.mapValues(_.bspDisplayName)}")

    map
  }

  lazy val rootModules: Seq[mill.main.RootModule] =
    RootModuleFinder.findRootModules(projectRoot, baseLogger)

  lazy val bspIdByModule: Map[BspModule, BuildTargetIdentifier] = bspModulesById.map(_.swap)
}
