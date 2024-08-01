package mill.bsp.worker

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import mill.scalalib.bsp.BspModule
import mill.scalalib.internal.JavaModuleUtils
import mill.define.Module
import mill.eval.Evaluator

private class State(workspaceDir:os.Path,evaluators: Seq[Evaluator], debug: String => Unit) {
  lazy val bspModulesById: Map[BuildTargetIdentifier, (BspModule, Evaluator)] = {
    val modules: Seq[(Module, Seq[Module], Evaluator)] = evaluators
      .map(ev => (ev.rootModule, JavaModuleUtils.transitiveModules(ev.rootModule), ev))

    val map = modules
      .flatMap { case (rootModule, otherModules, eval) =>
        (Seq(rootModule) ++ otherModules).collect {
          case m: BspModule =>
            val uri = Utils.sanitizeUri(
              rootModule.millSourcePath /
                m.millOuterCtx.foreign.fold(List.empty[String])(_.parts) /
                m.millModuleSegments.parts
            )

            (new BuildTargetIdentifier(uri), (m, eval))
        }
      }
      .toMap
    debug(s"BspModules: ${map.view.mapValues(_._1.bspDisplayName).toMap}")

    map
  }

  lazy val rootModules: Seq[mill.define.BaseModule] = evaluators.map(_.rootModule)

  lazy val bspIdByModule: Map[BspModule, BuildTargetIdentifier] =
    bspModulesById.view.mapValues(_._1).map(_.swap).toMap
  lazy val syntheticRootBspBuildTarget:Option[SyntheticRootBspBuildTargetData] = {
    def containsWorkspaceDir(path: Option[os.Path]) = path.exists(workspaceDir.startsWith)

    if (bspModulesById.values.exists { case (m, _) => containsWorkspaceDir(m.bspBuildTarget.baseDirectory) }) None else Some(new SyntheticRootBspBuildTargetData(workspaceDir))
  }
}
