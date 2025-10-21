package mill.bsp.worker

import ch.epfl.scala.bsp4j.{BuildTargetIdentifier, SourceItem, SourceItemKind, SourcesItem, BuildTarget}
import mill.bsp.worker.Utils.{makeBuildTarget, sanitizeUri}
import mill.api.daemon.internal.bsp.BspModuleApi.Tag
import mill.api.daemon.internal.bsp.BspBuildTarget

import scala.jdk.CollectionConverters._

/**
 * Synthetic BSP build target for a script file.
 *
 * Script files are standalone `.scala`, `.java`, or `.kotlin` files with a `//|` build header
 * that can be run independently. This class creates synthetic IntelliJ modules for them.
 *
 * @param scriptPath The path to the script file
 */
class SyntheticScriptBspTarget(val scriptPath: os.Path) {

  private val moduleName = s"script-${scriptPath.baseName}"

  val id: BuildTargetIdentifier = new BuildTargetIdentifier(
    sanitizeUri(scriptPath.toNIO)
  )

  val bt: BspBuildTarget = BspBuildTarget(
    displayName = Some(moduleName),
    baseDirectory = Some(scriptPath.toNIO),
    tags = Seq(Tag.Application),
    languageIds = Seq(languageIdFromExtension(scriptPath.ext)),
    canCompile = true,
    canTest = false,
    canRun = true,
    canDebug = false
  )

  val target: BuildTarget = makeBuildTarget(id, Seq.empty, bt, None)

  def synthSources: SourcesItem = new SourcesItem(
    id,
    Seq(new SourceItem(sanitizeUri(scriptPath.toNIO), SourceItemKind.FILE, false)).asJava
  )

  private def languageIdFromExtension(ext: String): String = ext match {
    case "scala" => "scala"
    case "java" => "java"
    case "kotlin" => "kotlin"
    case _ => "unknown"
  }
}

object SyntheticScriptBspTarget {
  /**
   * Creates synthetic BSP targets for all script files in the workspace.
   *
   * @param workspaceDir The workspace root directory
   * @param outDir The output directory to exclude
   * @return A sequence of synthetic script BSP targets
   */
  def discoverScriptTargets(
      workspaceDir: os.Path,
      outDir: os.Path
  ): Seq[SyntheticScriptBspTarget] = {
    mill.script.ScriptFileDiscovery
      .discoverScriptFiles(workspaceDir, outDir)
      .map(new SyntheticScriptBspTarget(_))
  }
}
