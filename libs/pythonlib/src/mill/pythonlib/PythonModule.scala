package mill.pythonlib

import mill.*
import mill.api.internal.PathAliasing
import mill.api.Result
import mill.constants.DaemonFiles
import mill.util.Jvm
import mill.api.TaskCtx
import mill.javalib.JavaHomeModule
import mill.api.BuildCtx

trait PythonModule extends PipModule with DefaultTaskModule with JavaHomeModule { outer =>

  /**
   *  The direct dependencies of this module.
   *  This is meant to be overridden to add dependencies.
   */
  def moduleDeps: Seq[PythonModule] = Nil

  /**
   * Python interpreter found on the host system. This will be used to create a
   * new virtual environment, which will be used by all tasks in this module.
   *
   * If you'd like to use a specific python version, override this task to
   * point to a specific python executable.
   *
   * Examples:
   *
   * ```
   * // use whatever python version is installed on the host system (default)
   * def hostPythonCommand = T{ "python3" }
   *
   * // use a specific minor release
   * def hostPythonCommand = T{ "python3.12" }
   *
   * // use a specific executable file
   * def hostPythonCommand = T{ "/usr/bin/python3" }
   * ```
   */
  def hostPythonCommand: T[String] = Task { "python3" }

  /*
   * Initalize a virtual environment for this module, and install all libraries and tools
   * needed by this module and its dependencies.
   */
  def venv: T[PathRef] = Task {
    val venv = Task.dest / "venv"
    os.call((hostPythonCommand(), "-m", "venv", venv))
    os.call(
      (venv / "bin/python3", "-m", "pip", "install", pipInstallArgs().args),
      stdout = os.Inherit
    )
    PathRef(venv)
  }

  /*
   * The path to the binary directory of the virtual environment which has been
   * initialized to contain all libraries and tools needed by this module and its
   * dependencies.
   */
  def venvBin: T[PathRef] = Task {
    PathRef(venv().path / "bin")
  }

  /**
   * An executable python interpreter. This interpreter is set up to run in a
   * virtual environment which has been initialized to contain all libraries and
   * tools needed by this module and its dependencies.
   */
  def pythonExe: T[PathRef] = Task {
    val python = venvBin().path / "python3"
    PathRef(python)
  }

  /**
   * The folders where the source files for this mill module live.
   *
   * Python modules will be defined relative to these directories.
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

  override def pythonToolDeps: T[Seq[String]] = Task {
    super.pythonToolDeps() ++ Seq(
      "mypy==1.13.0",
      "pex==2.24.1"
    )
  }

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

  /**
   * Command-line options to pass as bundle configuration defined by the user.
   */
  def bundleOptions: T[Seq[String]] = Task { Seq.empty[String] }

  // TODO: right now, any task that calls this helper will have its own python
  // cache. This is slow. Look into sharing the cache between tasks.
  def runner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      // `Jvm.realAbs`: passed verbatim to `Runtime.exec` as the executable path; OS-level
      // exec does not honor our spawnHook-installed cwd aliases.
      command0 = Jvm.realAbs(pythonExe()),
      options = pythonOptions(),
      env0 = runnerEnvTask() ++ forkEnv(),
      workingDir0 = Task.dest
    )
  }

  private def runnerEnvTask = Task.Anon {
    Map(
      // `Jvm.realAbs`: python's `sys.path` entries must be absolute — python `chdir`s during
      // imports and resolves `sys.path` lazily, so relative paths drift.
      "PYTHONPATH" -> transitivePythonPath().map(Jvm.realAbs)
        .mkString(java.io.File.pathSeparator),
      // `Jvm.realAbs`: python writes cache files at this prefix from any cwd it chdirs to.
      "PYTHONPYCACHEPREFIX" -> Jvm.realAbs(Task.dest / "cache"),
      if (Task.log.prompt.colored) { "FORCE_COLOR" -> "1" }
      else { "NO_COLOR" -> "1" }
      // `Jvm.realAbs`: pyspark resolves `$JAVA_HOME/bin/java` via `os.path.join` (string concat),
      // then `subprocess.Popen` resolves it against pyspark's own cwd which may differ.
    ) ++ javaHome().map(jh => "JAVA_HOME" -> Jvm.realAbs(jh))
  }

  /**
   * Run a typechecker on this module.
   */
  def typeCheck: T[Unit] = Task {
    runner().run(
      (
        // format: off
        "-m", "mypy",
        "--strict",
        // `Jvm.realAbs`: mypy stores incremental cache entries keyed by absolute path.
        "--cache-dir", Jvm.realAbs(Task.dest / "mypycache"),
        sources().map(_.path)
        // format: on
      )
    )
  }

  /**
   * Run the main python script of this module.
   *
   * @see [[mainScript]]
   */
  def run(args: mill.api.Args) = Task.Command {
    runner().run(
      args = (
        // `Jvm.realAbs`: python records `__file__` for the entry script and uses it across chdirs.
        Jvm.realAbs(mainScript()),
        args.value
      )
    )
  }

  /**
   * Run the main python script of this module.
   *
   * @see [[mainScript]]
   */
  def runBackground(args: mill.api.Args) = Task.Command(persistent = true) {
    val backgroundPaths = mill.javalib.RunModule.BackgroundPaths(Task.dest)
    val pwd0 = os.Path(java.nio.file.Paths.get(".").toAbsolutePath)

    BuildCtx.withFilesystemCheckerDisabled {
      Jvm.spawnProcess(
        mainClass = "mill.javalib.backgroundwrapper.MillBackgroundWrapper",
        classPath = mill.javalib.JvmWorkerModule.backgroundWrapperClasspath().map(_.path).toSeq,
        jvmArgs = Nil,
        env = runnerEnvTask(),
        mainArgs = backgroundPaths.toArgs ++ Seq(
          "<subprocess>",
          // `Jvm.realAbs`: the background wrapper relaunches these as a detached child whose cwd
          // is independent of this task's sandbox, so the aliases set up here aren't reachable.
          Jvm.realAbs(pythonExe()),
          Jvm.realAbs(mainScript())
        ) ++ args.value,
        cwd = BuildCtx.workspaceRoot,
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

  /** Bundles the project into a single PEX executable(bundle.pex). */
  def bundle = Task {
    val pexFile = Task.dest / "bundle.pex"
    // `Jvm.realAbs` (all three): pex bakes these paths into the bundled `.pex` archive and
    // reads them back when the bundle runs from arbitrary user cwds.
    runner().run(
      (
        // format: off
        "-m", "pex",
        transitivePythonDeps().toSeq,
        transitivePythonPath().flatMap(pr =>
          Seq("-D", Jvm.realAbs(pr))
        ),
        "--exe", Jvm.realAbs(mainScript()),
        "-o", Jvm.realAbs(pexFile),
        bundleOptions()
        // format: on
      ),
      workingDir = Task.dest
    )
    PathRef(pexFile)
  }

  trait PythonTests extends PythonModule {
    override def moduleDeps: Seq[PythonModule] = Seq(outer)

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
  trait Runner {
    def run(
        args: os.Shellable = Seq(),
        command: String = null,
        env: Map[String, String] = null,
        workingDir: os.Path = null
    )(using ctx: TaskCtx): Unit
  }

  private class RunnerImpl(
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
      val effectiveCwd = Option(workingDir).getOrElse(workingDir0)
      PathAliasing.prepareSubprocessCwd(effectiveCwd, taskDest = Some(ctx.dest))
      val effectiveEnv = Option(env).getOrElse(env0)
      val commandArgs =
        PathAliasing.withSubprocessPathSerializer(effectiveCwd, taskDest = Some(ctx.dest)) {
          Seq(Option(command).getOrElse(command0)) ++ options ++ args.value
        }

      os.call(
        cmd = commandArgs,
        env = PathAliasing.subprocessEnv(effectiveEnv, effectiveCwd, taskDest = Some(ctx.dest)),
        cwd = effectiveCwd,
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = true
      )
    }
  }
}
