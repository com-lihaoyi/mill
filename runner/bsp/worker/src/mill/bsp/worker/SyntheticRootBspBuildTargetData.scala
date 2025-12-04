package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, SourceItem, SourceItemKind, SourcesItem}
import mill.bsp.worker.Utils.{makeBuildTarget, sanitizeUri}
import mill.api.daemon.internal.bsp.BspModuleApi.Tag

import scala.jdk.CollectionConverters._
import ch.epfl.scala.bsp4j.BuildTarget
import mill.api.daemon.internal.bsp.BspBuildTarget

/**
 * Synthesised [[BspBuildTarget]] to handle exclusions.
 * Intellij-Bsp doesn't provide a way to exclude files outside of module,so if there is no
 * module having content root of [[topLevelProjectRoot]], [[SyntheticRootBspBuildTargetData]]
 * will be created
 */
class SyntheticRootBspBuildTargetData(topLevelProjectRoot: os.Path) {
  val id: BuildTargetIdentifier = BuildTargetIdentifier(
    Utils.sanitizeUri((topLevelProjectRoot / "mill-synthetic-root-target").toNIO)
  )

  val bt: BspBuildTarget = BspBuildTarget(
    displayName = Some("mill-synthetic-root"),
    baseDirectory = Some(topLevelProjectRoot.toNIO),
    tags = Seq(Tag.Manual),
    languageIds = Seq.empty,
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false
  )

  val target: BuildTarget = makeBuildTarget(id, Seq.empty, bt, None)
  private val sourcePath = topLevelProjectRoot / "src"
  def synthSources = SourcesItem(
    id,
    Seq(SourceItem(sanitizeUri(sourcePath.toNIO), SourceItemKind.DIRECTORY, false)).asJava
  ) // intellijBSP does not create contentRootData for module with only outputPaths (this is probably a bug)
}
object SyntheticRootBspBuildTargetData {
  def makeIfNeeded(workspaceDir: os.Path): Option[SyntheticRootBspBuildTargetData] =
    Some(SyntheticRootBspBuildTargetData(workspaceDir))
}
