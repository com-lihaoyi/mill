package mill.internal

import mill.constants.{ConfigConstants, EnvVars, OutFiles, OutFolderMode}

import scala.jdk.CollectionConverters.*

private[mill] object OutputDirectoryLayout {
  def regularOutDir(env: Map[String, String]): String =
    env.getOrElse(EnvVars.MILL_OUTPUT_DIR, OutFiles.OutFiles.defaultOut)

  private def separateBspOutputDirFromHeader(workDir: os.Path): Boolean =
    Util.readBooleanFromBuildHeader(
      workDir,
      ConfigConstants.millSeparateBspOutputDir,
      mill.constants.CodeGenConstants.rootBuildFileNames.asScala.toSeq
    )

  def bspOutOverride(workDir: os.Path, env: Map[String, String]): Option[String] =
    env.get(EnvVars.MILL_BSP_OUTPUT_DIR).orElse {
      Option.unless(env.get(EnvVars.MILL_NO_SEPARATE_BSP_OUTPUT_DIR).contains("1")) {
        Option.when(separateBspOutputDirFromHeader(workDir))(OutFiles.OutFiles.defaultBspOut)
      }.flatten
    }

  private def effectiveEnvForOutMode(
      outMode: OutFolderMode,
      workDir: os.Path,
      env: Map[String, String]
  ): Map[String, String] =
    outMode match {
      case OutFolderMode.REGULAR => env
      case OutFolderMode.BSP =>
        bspOutOverride(workDir, env) match {
          case Some(dir) if !env.contains(EnvVars.MILL_BSP_OUTPUT_DIR) =>
            env + (EnvVars.MILL_BSP_OUTPUT_DIR -> dir)
          case _ => env
        }
    }

  def outDir(outMode: OutFolderMode, workDir: os.Path, env: Map[String, String]): String =
    outMode match {
      case OutFolderMode.REGULAR => regularOutDir(env)
      case OutFolderMode.BSP => bspOutOverride(workDir, env).getOrElse(regularOutDir(env))
    }

  /**
   * One-shot resolver for callers (subprocess launchers) that need all three
   * pieces of out-mode-resolved state at once. Returns the env mutated with
   * `MILL_BSP_OUTPUT_DIR` if applicable, the active out dir, and the regular
   * (non-BSP) out dir.
   */
  case class Resolved(effectiveEnv: Map[String, String], outDir: String, regularOutDir: String)
  def resolve(outMode: OutFolderMode, workDir: os.Path, env: Map[String, String]): Resolved = {
    val eff = effectiveEnvForOutMode(outMode, workDir, env)
    Resolved(eff, outDir(outMode, workDir, eff), regularOutDir(eff))
  }
}
