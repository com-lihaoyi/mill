package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.JavaModuleUtils
import mill.define.{Evaluator, Module}

private class BspEvaluators(
    workspaceDir: os.Path,
    evaluators: Seq[Evaluator],
    debug: (() => String) => Unit
) {
  lazy val bspModulesIdList: Seq[(BuildTargetIdentifier, (BspModule, Evaluator))] = {
    val modules: Seq[(Module, Seq[Module], Evaluator)] = evaluators
      .map(ev => (ev.rootModule, JavaModuleUtils.transitiveModules(ev.rootModule), ev))

    modules
      .flatMap { case (rootModule, modules, eval) =>
        modules.collect {
          case m: BspModule =>
            val uri = Utils.sanitizeUri(
              rootModule.moduleDir / m.moduleSegments.parts
            )

            (new BuildTargetIdentifier(uri), (m, eval))
        }
      }
  }
  lazy val bspModulesById: Map[BuildTargetIdentifier, (BspModule, Evaluator)] = {
    val map = bspModulesIdList.toMap
    debug(() => s"BspModules: ${map.view.mapValues(_._1.bspDisplayName).toMap}")
    map
  }

  lazy val rootModules: Seq[mill.define.BaseModule] = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModule, BuildTargetIdentifier] =
    bspModulesById.view.mapValues(_._1).map(_.swap).toMap
  lazy val syntheticRootBspBuildTarget: Option[SyntheticRootBspBuildTargetData] =
    SyntheticRootBspBuildTargetData.makeIfNeeded(bspModulesById.values.map(_._1), workspaceDir)

  def filterNonSynthetic(input: java.util.List[BuildTargetIdentifier])
      : java.util.List[BuildTargetIdentifier] = {
    import scala.jdk.CollectionConverters.*
    input.asScala.filterNot(syntheticRootBspBuildTarget.map(_.id).contains).toList.asJava
  }
}
