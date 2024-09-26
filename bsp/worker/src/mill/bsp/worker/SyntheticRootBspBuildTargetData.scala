package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, SourceItem, SourceItemKind, SourcesItem}
import mill.bsp.worker.Utils.{makeBuildTarget, sanitizeUri}
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.scalalib.bsp.BspModule.Tag

import scala.jdk.CollectionConverters._
import ch.epfl.scala.bsp4j.BuildTarget

/**
 * Synthesised [[BspBuildTarget]] to handle exclusions.
 * Intellij-Bsp doesn't provide a way to exclude files outside of module,so if there is no module having content root of [[topLevelProjectRoot]], [[SyntheticRootBspBuildTargetData]] will be created
 */
class SyntheticRootBspBuildTargetData(topLevelProjectRoot: os.Path) {
  val id: BuildTargetIdentifier = new BuildTargetIdentifier(
    Utils.sanitizeUri(topLevelProjectRoot / "mill-synthetic-root-target")
  )

  val bt: BspBuildTarget = BspBuildTarget(
    displayName = Some("mill-synthetic-root"),
    baseDirectory = Some(topLevelProjectRoot),
    tags = Seq(Tag.Manual),
    languageIds = Seq.empty,
    canCompile = false,
    canTest = false,
    canRun = false,
    canDebug = false
  )

  val target: BuildTarget = makeBuildTarget(id, Seq.empty, bt, None)
  private val sourcePath = topLevelProjectRoot / "src"
  def synthSources = new SourcesItem(
    id,
    Seq(new SourceItem(sanitizeUri(sourcePath), SourceItemKind.DIRECTORY, false)).asJava
  ) // intellijBSP does not create contentRootData for module with only outputPaths (this is probably a bug)
}
object SyntheticRootBspBuildTargetData {
  def makeIfNeeded(
      existingModules: Iterable[BspModule],
      workspaceDir: os.Path
  ): Option[SyntheticRootBspBuildTargetData] = {
    def containsWorkspaceDir(path: Option[os.Path]) = path.exists(workspaceDir.startsWith)
    if (existingModules.exists { m => containsWorkspaceDir(m.bspBuildTarget.baseDirectory) }) None
    else Some(new SyntheticRootBspBuildTargetData(workspaceDir))
  }
}
