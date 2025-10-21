package mill.script

/**
 * Utility for discovering script files in the workspace.
 *
 * Script files are files with `.scala`, `.java`, or `.kotlin` extension
 * that start with a `//|` build header comment and are not in the `out/` folder.
 */
object ScriptFileDiscovery {

  private val scriptExtensions = Set("scala", "java", "kotlin")

  /**
   * Discovers all script files in the given workspace directory.
   *
   * @param workspaceDir The root workspace directory to search
   * @param outDir The output directory to exclude (typically `workspaceDir / "out"`)
   * @return A sequence of paths to script files
   */
  def discoverScriptFiles(workspaceDir: os.Path, outDir: os.Path): Seq[os.Path] = {
    if (!os.exists(workspaceDir)) return Seq.empty

    os.walk(workspaceDir)
      .filter { path =>
        // Check if it's a file with the right extension
        os.isFile(path) &&
        scriptExtensions.contains(path.ext) &&
        // Exclude files in the out/ directory
        !path.startsWith(outDir) &&
        // Check if file starts with //| header
        hasScriptHeader(path)
      }
  }

  /**
   * Checks if a file starts with a `//|` build header comment.
   */
  private def hasScriptHeader(path: os.Path): Boolean = {
    try {
      val lines = os.read.lines(path)
      lines.headOption.exists(_.startsWith("//|"))
    } catch {
      case _: Exception => false
    }
  }
}
