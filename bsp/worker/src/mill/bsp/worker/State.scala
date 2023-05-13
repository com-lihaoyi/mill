package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildTarget, BuildTargetIdentifier}
import mill.runner.MillBuildBootstrap
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.ModuleUtils
import mill.define.Module
import mill.util.ColorLogger

private class State(projectRoot: os.Path, baseLogger: ColorLogger, debug: String => Unit) {
  lazy val bspModulesById: Map[BuildTargetIdentifier, BspModule] = {
    val modules: Seq[(Module, Seq[Module])] = rootModules
      .map(rootModule => (rootModule, ModuleUtils.transitiveModules(rootModule)))

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
    debug(s"BspModules: ${map.mapValues(_.bspDisplayName)}")

    map
  }

  lazy val rootModules: Seq[mill.main.RootModule] = {
    val evaluated = new mill.runner.MillBuildBootstrap(
      projectRoot = projectRoot,
      home = os.home,
      keepGoing = false,
      imports = Nil,
      env = Map.empty,
      threadCount = None,
      targetsAndParams = Seq("resolve", "_"),
      prevRunnerState = mill.runner.RunnerState.empty,
      logger = baseLogger
    ).evaluate()

    val rootModules0 = evaluated.result.frames
      .flatMap(_.classLoaderOpt)
      .zipWithIndex
      .map { case (c, i) =>
        MillBuildBootstrap
          .getRootModule(c, i, projectRoot)
          .fold(sys.error(_), identity(_))
      }

    val bootstrapModule = evaluated.result.bootstrapModuleOpt.map(m =>
      MillBuildBootstrap
        .getChildRootModule(
          m,
          evaluated.result.frames.length,
          projectRoot
        )
        .fold(sys.error(_), identity(_))
    )

    rootModules0 ++ bootstrapModule
  }
  lazy val bspIdByModule: Map[BspModule, BuildTargetIdentifier] = bspModulesById.map(_.swap)
}
