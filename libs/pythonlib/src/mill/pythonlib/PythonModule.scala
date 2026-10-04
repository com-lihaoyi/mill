package mill.pythonlib

import mill.*
import mill.api.Result
import mill.constants.DaemonFiles
import mill.util.Jvm
import mill.api.TaskCtx
import mill.javalib.JavaHomeModule
import mill.api.BuildCtx
import mill.api.internal.PathAliasing

trait PythonModule extends UvModule with DefaultTaskModule with JavaHomeModule { outer =>

  /**
   *  The direct dependencies of this module.
   *  This is meant to be overridden to add dependencies.
   */
  def moduleDeps: Seq[PythonModule] = Nil

  /**
   * Python version request passed to uv when creating the virtual environment.
   *
   * If you'd like to use a specific python version, override this task to
   * point to a specific python executable.
   *
   * Examples:
   *
   * ```
   * // use Mill's default modern Python version
   * def pythonVersion = T{ "3.12" }
   *
   * // use a specific minor release
   * def pythonVersion = T{ "3.13" }
   *
   * // use a specific executable file
   * def pythonVersion = T{ "/usr/bin/python3" }
   * ```
   */
  def pythonVersion: T[String] = Task { "3.12" }

  private def venvBinPath(venv: os.Path): os.Path =
    venv / (if (mill.constants.Util.isWindows) "Scripts" else "bin")

  private def venvExecutable(venv: os.Path, name: String): os.Path =
    venvBinPath(venv) / (name + (if (mill.constants.Util.isWindows) ".exe" else ""))

  /*
   * Initialize a virtual environment for this module, and install all libraries and tools
   * needed by this module and its dependencies.
   */
  def venv: T[PathRef] = Task {
    val venv = Task.dest / "venv"
    val uvEnv = uvEnvTask()
    os.call(
      (uvExe().path, "venv", "--python", pythonVersion(), venv),
      env = uvEnv,
      stdout = os.Inherit
    )
    val python = venvExecutable(venv, "python")
    val installArgs = uvInstallArgs().args
    if (installArgs != uvIndexArgs()) {
      os.call(
        (
          uvExe().path,
          "pip",
          "install",
          "--python",
          python,
          "--strict",
          installArgs
        ),
        env = uvEnv,
        stdout = os.Inherit
      )
    }
    PathRef(venv)
  }

  /*
   * The path to the binary directory of the virtual environment which has been
   * initialized to contain all libraries and tools needed by this module and its
   * dependencies.
   */
  def venvBin: T[PathRef] = Task {
    PathRef(venvBinPath(venv().path))
  }

  /**
   * An executable python interpreter. This interpreter is set up to run in a
   * virtual environment which has been initialized to contain all libraries and
   * tools needed by this module and its dependencies.
   */
  def pythonExe: T[PathRef] = Task {
    PathRef(venvExecutable(venv().path, "python"))
  }

  /**
   * The paths where the source files for this Mill module live.
   *
   * Standard [[PythonModule]]s return source directories, while
   * [[BarePythonModule]] returns individual files whose import names are based
   * on their paths relative to the workspace root.
   */
  def sources: T[Seq[PathRef]] = Task.Sources("src")

  /**
   * The folders where the resource files for this module live.
   */
  def resources: T[Seq[PathRef]] = Task.Sources { "resources" }

  /**
   * The python script to run. This file may not exist if this module is only a library.
   */
  def mainScript: T[PathRef] = Task.Source("src/main.py")

  /** The isolated ty tool environment managed and cached by uv. */
  def tyTool: T[String] = Task { "ty==0.0.84" }

  /** The isolated PEX tool environment managed and cached by uv. */
  def pexTool: T[String] = Task { "pex==2.103.4" }

  /**
   * Additional directories to include in the PYTHONPATH directly. These paths
   * are "unmanaged": they'll be included as they are on disk.
   */
  def unmanagedPythonPath: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * Folders containing source files that are generated rather than
   * handwritten; these files can be generated in this task itself,
   * or can refer to files generated from other tasks
   */
  def generatedSources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * The directories used to construct the PYTHONPATH for this module, used for
   * execution, excluding upstream modules.
   *
   * This includes source directories, resources and other unmanaged
   * directories.
   */
  def localPythonPath: T[Seq[PathRef]] = Task {
    sources() ++ resources() ++ generatedSources() ++ unmanagedPythonPath()
  }

  /**
   * The transitive version of [[localPythonPath]]: this includes the
   * directories of all upstream modules as well.
   */
  def transitivePythonPath: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitivePythonPath)().flatten
    localPythonPath() ++ upstream
  }

  /**
   * Any environment variables you want to pass to the forked Env
   */
  def forkEnv: T[Map[String, String]] = Task { Map.empty[String, String] }

  /**
   * Command-line options to pass to the Python Interpreter defined by the user.
   */
  def pythonOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /** Additional interpreter options for a second and later related invocation. */
  protected def repeatedPythonOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Command-line options to pass as bundle configuration defined by the user.
   */
  def bundleOptions: T[Seq[String]] = Task { Seq.empty[String] }

  // TODO: right now, any task that calls this helper will have its own python
  // cache. This is slow. Look into sharing the cache between tasks.
  def runner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      command0 = pythonExe().path,
      options = pythonOptions(),
      pythonPath = transitivePythonPath().map(_.path),
      pythonPycachePrefix = Task.dest / "cache",
      forceColor = Task.log.prompt.colored,
      javaHome = javaHome().map(_.path),
      venv = venv().path,
      venvBin = venvBin().path,
      inheritedPath = PythonModule.pathEnvironmentValue(Task.env),
      forkEnv0 = PathAliasing.withRawPathSerializer(forkEnv()),
      workingDir0 = Task.dest
    )
  }

  /** A runner for invoking uv with the module environment. */
  def uvRunner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.ExternalRunnerImpl(
      command0 = PathRef.toAbsString(uvExe().path),
      options = Nil,
      env0 = PathAliasing.withRawPathSerializer(uvEnvTask()),
      workingDir0 = Task.dest
    )
  }

  private def processEnvTask = Task.Anon {
    Map(
      if (Task.log.prompt.colored) { "FORCE_COLOR" -> "1" }
      else { "NO_COLOR" -> "1" }
    ) ++ javaHome().map(javaHome => "JAVA_HOME" -> javaHome.path.toString)
  }

  private def uvEnvTask = Task.Anon {
    PythonModule.uvEnvironment(processEnvTask(), forkEnv(), Task.offline)
  }

  /**
   * Run a typechecker on this module.
   */
  def typeCheck: T[Unit] = Task {
    uvRunner().run(
      (
        // format: off
        "tool", "run",
        uvIndexArgs(),
        "--from", tyTool(),
        "ty",
        "check",
        "--python", PathRef.toRelString(pythonExe().path, Task.dest),
        transitivePythonPath().filter(path => os.exists(path.path)).flatMap(path =>
          Seq("--extra-search-path", PathRef.toRelString(path.path, Task.dest))
        ),
        sources().map(path => PathRef.toRelString(path.path, Task.dest))
        // format: on
      ),
      workingDir = Task.dest
    )
  }

  /**
   * Run the main python script of this module.
   *
   * @see [[mainScript]]
   */
  def run(args: mill.api.Args) = Task.Command {
    runner().run(
      args = Seq(PathRef.toRelString(mainScript().path, Task.dest)) ++ args.value,
      workingDir = Task.dest
    )
  }

  /**
   * Run the main python script of this module.
   *
   * @see [[mainScript]]
   */
  def runBackground(args: mill.api.Args) = Task.Command(persistent = true) {
    val backgroundPaths = mill.javalib.RunModule.BackgroundPaths(Task.dest)
    val cwd = BuildCtx.workspaceRoot
    val pwd0 = os.Path(java.nio.file.Paths.get(".").toAbsolutePath)

    BuildCtx.withFilesystemCheckerDisabled {
      Jvm.spawnProcess(
        mainClass = "mill.javalib.backgroundwrapper.MillBackgroundWrapper",
        classPath = mill.javalib.JvmWorkerModule.backgroundWrapperClasspath().map(_.path).toSeq,
        jvmArgs = Nil,
        env = PythonModule.runnerEnv(
          pythonPath = transitivePythonPath().map(_.path),
          pythonPycachePrefix = Task.dest / "cache",
          forceColor = Task.log.prompt.colored,
          javaHome = javaHome().map(_.path),
          venv = venv().path,
          venvBin = venvBin().path,
          inheritedPath = PythonModule.pathEnvironmentValue(Task.env),
          cwd = cwd,
          // The detached background process can't resolve `../mill-workspace` aliases.
          relativize = false
        ) ++ PathAliasing.withRawPathSerializer(forkEnv()),
        mainArgs = backgroundPaths.toArgs ++ Seq(
          "<subprocess>",
          // `MillBackgroundWrapper` is a detached process that relaunches these via plain
          // `java.nio`/`ProcessBuilder`, so its cwd and our path aliases aren't reachable, and any
          // ephemeral `mill-no-daemon/<id>/mill-workspace` forwarder it routes through is deleted
          // when the launcher exits: pass real absolute paths with symlinks resolved.
          //
          // For the interpreter, resolve only the enclosing `venv/bin` directory (to strip any
          // `mill-workspace` forwarder from the prefix) but keep the final `python3` symlink
          // unresolved: fully resolving it would point at the base interpreter and Python would
          // no longer detect the virtualenv (via the adjacent `pyvenv.cfg`), losing the venv's
          // installed packages.
          PathRef.toResolvedPathString(pythonExe().path / os.up) + "/" + pythonExe().path.last
        ) ++ pythonOptions() ++ Seq(PathRef.toResolvedPathString(mainScript().path)) ++ args.value,
        cwd = cwd,
        stdin = "",
        // Hack to forward the background subprocess output to the Mill server process
        // stdout/stderr files, so the output will get properly slurped up by the Mill server
        // and shown to any connected Mill client even if the current command has completed
        stdout = os.PathAppendRedirect(pwd0 / ".." / DaemonFiles.stdout),
        stderr = os.PathAppendRedirect(pwd0 / ".." / DaemonFiles.stderr),
        javaHome = javaHome().map(_.path)
      )
    }
    ()
  }

  override def defaultTask(): String = "run"

  /**
   * Opens up a Python console with your module and all dependencies present,
   * for you to test and operate your code interactively.
   */
  def console(): Command[Unit] = Task.Command(exclusive = true) {
    if (!mill.constants.Util.hasConsole()) {
      Task.fail("console needs to be run with the -i/--interactive flag")
    } else {
      runner().run()
      ()
    }
  }

  /** Bundles the project into a self-contained native PEX SCIE executable. */
  def bundle = Task {
    if (Task.offline) {
      Task.fail(
        "PEX SCIE bundles cannot be built with --offline because PEX may need to download " +
          "the science launcher and portable Python assets"
      )
    }
    val bundleFile = Task.dest / (if (mill.constants.Util.isWindows) "bundle.exe" else "bundle")
    val sciePythonVersion = os.call(
      (
        pythonExe().path,
        "-c",
        "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
      )
    ).out.trim()
    val projectWheels = transitivePythonProjectDeps().distinctBy(_.path).zipWithIndex.map {
      case (project, index) =>
        val outputDir = Task.dest / "project-wheels" / index.toString
        uvRunner().run(
          (
            "build",
            "--python",
            pythonVersion(),
            uvIndexArgs(),
            "--wheel",
            "--clear",
            "--no-create-gitignore",
            "--out-dir",
            outputDir,
            project.path
          ),
          workingDir = Task.dest
        )
        os.list(outputDir).filter(_.ext == "whl") match {
          case Seq(wheel) => wheel
          case artifacts =>
            Task.fail(
              s"Expected exactly one wheel for local Python project ${project.path}, " +
                s"found: ${artifacts.mkString(", ")}"
            )
        }
    }
    val requirements = transitivePythonDeps() ++
      transitiveUnmanagedWheels().map(path => PathRef.toRelString(path.path, Task.dest)) ++
      projectWheels.map(path => PathRef.toRelString(path, Task.dest))
    val requirementFiles = transitivePythonRequirementFiles().filter { path =>
      os.exists(path.path) && os.read.lines(path.path).exists { line =>
        val trimmed = line.trim
        trimmed.nonEmpty && !trimmed.startsWith("#")
      }
    }
    val venvRepositoryArgs =
      if (requirements.nonEmpty || requirementFiles.nonEmpty)
        Seq("--venv-repository", PathRef.toRelString(venv().path, Task.dest))
      else Nil
    uvRunner().run(
      (
        // format: off
        "tool", "run",
        uvIndexArgs(),
        "--from", pexTool(),
        "pex",
        requirements,
        requirementFiles.flatMap(path =>
          Seq("-r", PathRef.toRelString(path.path, Task.dest))
        ),
        venvRepositoryArgs,
        transitivePythonPath().flatMap(pr =>
          Seq("-D", PathRef.toRelString(pr.path, Task.dest))
        ),
        "--exe", PathRef.toRelString(mainScript().path, Task.dest),
        "--scie", "eager",
        "--scie-python-version", sciePythonVersion,
        "--scie-only",
        "-o", PathRef.toRelString(bundleFile, Task.dest),
        bundleOptions()
        // format: on
      ),
      workingDir = Task.dest
    )
    PathRef(bundleFile)
  }

  trait PythonTests extends PythonModule {
    override def moduleDeps: Seq[PythonModule] = Seq(outer)

    override def pythonVersion: T[String] = outer.pythonVersion
    override def uvVersion: T[String] = outer.uvVersion
    override def uvDownloadUrl: T[String] = outer.uvDownloadUrl
    override def uvChecksumUrl: T[String] = outer.uvChecksumUrl
    override def uvExe: T[PathRef] = outer.uvExe
    override def indexes: T[Seq[String]] = outer.indexes

    // Inherit the outer module's JDK selection so tests run on the same Java as the
    // module under test (mirrors `JavaModule`'s nested test module). Without this,
    // e.g. a PySpark module pinning `jvmId` would still launch tests on the default JDK.
    override def jvmId: T[String] = outer.jvmId
    override def jvmVersion: T[String] = outer.jvmVersion
    override def jvmIndexVersion: T[String] = outer.jvmIndexVersion
    override def javaHome: T[Option[PathRef]] = outer.javaHome
  }

}

object PythonModule {

  /** A [[BarePythonModule]] available as `PythonModule.Bare`. */
  trait Bare extends BarePythonModule

  private[pythonlib] def pathEnvironmentValue(env: collection.Map[String, String]): String =
    env.collectFirst { case (key, value) if key.equalsIgnoreCase("PATH") => value }.getOrElse("")

  private[pythonlib] def uvEnvironment(
      defaults: Map[String, String],
      forkEnv: Map[String, String],
      offline: Boolean
  ): Map[String, String] = {
    val environment = defaults ++ forkEnv
    if (offline) environment.updated("UV_OFFLINE", "1") else environment
  }

  trait Runner {
    def run(
        args: os.Shellable = Seq(),
        command: String = null,
        env: Map[String, String] = null,
        workingDir: os.Path = null
    )(using ctx: TaskCtx): Unit
  }

  private class RunnerImpl(
      command0: os.Path,
      options: Seq[String],
      pythonPath: Seq[os.Path],
      pythonPycachePrefix: os.Path,
      forceColor: Boolean,
      javaHome: Option[os.Path],
      venv: os.Path,
      venvBin: os.Path,
      inheritedPath: String,
      forkEnv0: Map[String, String],
      workingDir0: os.Path
  ) extends Runner {
    def run(
        args: os.Shellable = Seq(),
        command: String = null,
        env: Map[String, String] = null,
        workingDir: os.Path = null
    )(using ctx: TaskCtx): Unit = {
      val cwd = Option(workingDir).getOrElse(workingDir0)
      PathAliasing.ensureProcessCwdAliases(cwd)
      val baseEnv = PythonModule.runnerEnv(
        pythonPath = pythonPath,
        pythonPycachePrefix = pythonPycachePrefix,
        forceColor = forceColor,
        javaHome = javaHome,
        venv = venv,
        venvBin = venvBin,
        inheritedPath = inheritedPath,
        cwd = cwd
      ) ++ forkEnv0
      os.call(
        cmd = Seq(Option(command).getOrElse(PathRef.toRelString(command0, cwd))) ++
          options ++ args.value,
        env = baseEnv ++ Option(env).getOrElse(Map.empty) ++
          PathAliasing.workspaceEnvVarsForCwd(cwd),
        cwd = cwd,
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = true
      )
    }
  }

  private class ExternalRunnerImpl(
      command0: String,
      options: Seq[String],
      env0: Map[String, String],
      workingDir0: os.Path
  ) extends Runner {
    def run(
        args: os.Shellable = Seq(),
        command: String = null,
        env: Map[String, String] = null,
        workingDir: os.Path = null
    )(using ctx: TaskCtx): Unit = {
      val cwd = Option(workingDir).getOrElse(workingDir0)
      PathAliasing.ensureProcessCwdAliases(cwd)
      os.call(
        cmd = Seq(Option(command).getOrElse(command0)) ++ options ++ args.value,
        env = env0 ++ Option(env).getOrElse(Map.empty) ++
          PathAliasing.workspaceEnvVarsForCwd(cwd),
        cwd = cwd,
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = true
      )
    }
  }

  private def runnerEnv(
      pythonPath: Seq[os.Path],
      pythonPycachePrefix: os.Path,
      forceColor: Boolean,
      javaHome: Option[os.Path],
      venv: os.Path,
      venvBin: os.Path,
      inheritedPath: String,
      cwd: os.Path,
      // When false, emit real absolute paths instead of `../mill-workspace` aliases. The detached
      // background process (`MillBackgroundWrapper`) relaunches via plain `ProcessBuilder` and
      // cannot reach Mill's cwd path aliases, so its env must not contain relativized paths.
      relativize: Boolean = true
  ): Map[String, String] = {
    def fmt(p: os.Path): String =
      if (relativize) PathRef.toRelString(p, cwd) else PathRef.toResolvedPathString(p)
    Map(
      "PYTHONPATH" -> pythonPath.map(fmt).mkString(java.io.File.pathSeparator),
      "PYTHONPYCACHEPREFIX" -> fmt(pythonPycachePrefix),
      "VIRTUAL_ENV" -> fmt(venv),
      "PATH" -> (fmt(venvBin) + java.io.File.pathSeparator + inheritedPath),
      if (forceColor) "FORCE_COLOR" -> "1" else "NO_COLOR" -> "1"
    ) ++ javaHome.map(jh => "JAVA_HOME" -> fmt(jh))
  }
}
