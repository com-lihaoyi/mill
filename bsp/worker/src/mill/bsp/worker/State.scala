package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.runner.MillBuildBootstrap
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.{JavaModuleUtils, ModuleUtils}
import mill.define.Module
import mill.eval.Evaluator
import mill.util.ColorLogger

private class State(
    projectRoot: os.Path,
    baseLogger: ColorLogger,
    debug: String => Unit,
    disableCallgraphInvalidation: Boolean
) {
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

  lazy val evaluators: Seq[mill.eval.Evaluator] = {
    val evaluated = new mill.runner.MillBuildBootstrap(
      projectRoot = projectRoot,
      home = os.home,
      keepGoing = false,
      imports = Nil,
      env = Map.empty,
      threadCount = None,
      targetsAndParams = Seq("resolve", "_"),
      prevRunnerState = mill.runner.RunnerState.empty,
      logger = baseLogger,
      disableCallgraphInvalidation = disableCallgraphInvalidation,
      needBuildSc = true
    ).evaluate()

    evaluated.result.frames.map(_.evaluator)
  }

  lazy val rootModules = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModule, BuildTargetIdentifier] =
    bspModulesById.mapValues(_._1).map(_.swap).toMap
}
