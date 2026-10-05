package mill.pythonlib

import mill.*
import mill.api.{BuildCtx, PathRef}
import mill.constants.EnvVars
import mill.constants.OutFiles.OutFiles.{bspOut, defaultBspOut, millBuild, out}

/**
 * A Python module whose source files live directly under [[moduleDir]], without
 * an additional `src/` directory.
 *
 * Source files keep their workspace-relative paths on `PYTHONPATH`. For
 * example, `foo/bar.py` is importable as `foo.bar`. Sources owned by nested
 * Python modules are excluded automatically.
 */
trait BarePythonModule extends PythonModule {

  /** File extensions discovered as bare Python sources. */
  def bareSourceExtensions: Set[String] = Set("py", "pyi")

  /** Directory names pruned while discovering bare Python sources. */
  def bareExcludedDirectoryNames: Set[String] = Set(
    ".git",
    ".hg",
    ".svn",
    ".venv",
    "venv",
    "env",
    ".tox",
    ".nox",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    "__pycache__"
  )

  private def bareSourceCandidates: T[Seq[PathRef]] = Task.Input {
    val workspaceRoot = BuildCtx.workspaceRoot
    val sourceRoot = moduleDir
    val nestedModuleDirs = moduleInternal.modules.tail.collect {
      case module: PythonModule =>
        module.moduleDir
    }.toSet
    val configuredOutputRoots = Seq(
      out,
      bspOut,
      defaultBspOut,
      millBuild
    ) ++ Seq(
      Task.env.get(EnvVars.MILL_OUTPUT_DIR),
      Task.env.get(EnvVars.MILL_BSP_OUTPUT_DIR)
    ).flatten
    val excludedOutputRoots = configuredOutputRoots.distinct.map(os.Path(_, workspaceRoot))
    val excludedDirs = nestedModuleDirs ++ excludedOutputRoots
    if (!os.exists(sourceRoot)) Seq.empty
    else {
      os.walk
        .stream(
          sourceRoot,
          skip = path =>
            excludedDirs.contains(path) ||
              (os.isDir(path) && bareExcludedDirectoryNames.contains(path.last))
        )
        .filter(path => os.isFile(path) && bareSourceExtensions.contains(path.ext))
        .toSeq
        .sortBy(_.toString)
        .map(PathRef(_))
    }
  }

  override def sources: T[Seq[PathRef]] = Task {
    val projectDependencyDirs = transitivePythonProjectDeps().map(_.path)
    bareSourceCandidates().filterNot(source =>
      projectDependencyDirs.exists(project => source.path.startsWith(project))
    )
  }

  /**
   * A filtered import tree that preserves each source file's path relative to
   * the workspace root.
   */
  def barePythonPath: T[PathRef] = Task {
    val root = Task.dest / "pythonpath"
    for (source <- sources()) {
      val sourcePath = source.path
      val relativePath = sourcePath.relativeTo(BuildCtx.workspaceRoot)
      os.copy.over(sourcePath, root / relativePath, createFolders = true)
    }
    PathRef(root)
  }

  override def localPythonPath: T[Seq[PathRef]] = Task {
    Seq(barePythonPath()) ++ resources() ++ generatedSources() ++ unmanagedPythonPath()
  }

  /** The conventional `main.py` before it is copied into [[barePythonPath]]. */
  def bareMainScript: T[PathRef] = Task.Source(moduleDir / "main.py")

  override def mainScript: T[PathRef] = Task {
    val sourcePath = bareMainScript().path
    val relativePath = sourcePath.relativeTo(BuildCtx.workspaceRoot)
    PathRef(barePythonPath().path / relativePath)
  }
}
