package mill.pythonlib

import mill.*
import mill.api.Result
import mill.constants.DaemonFiles
import mill.util.Jvm
import mill.api.TaskCtx
import mill.javalib.JavaHomeModule
import mill.api.BuildCtx
import mill.api.opt.*

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
  def pythonOptions: T[Opts] = Task { Opts() }

  /** Additional interpreter options for a second and later related invocation. */
  protected def repeatedPythonOptions: T[Opts] = Task { Opts() }

  /**
   * Command-line options to pass as bundle configuration defined by the user.
   */
  def bundleOptions: T[Opts] = Task { Opts() }

  // TODO: right now, any task that calls this helper will have its own python
  // cache. This is slow. Look into sharing the cache between tasks.
  def runner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      command0 = pythonExe().path.toString,
      options = pythonOptions().toStringSeq,
      env0 = runnerEnvTask() ++ forkEnv(),
      workingDir0 = Task.dest
    )
  }

  /** A runner for invoking uv with the module environment. */
  def uvRunner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      command0 = uvExe().path.toString,
      options = Nil,
      env0 = uvEnvTask(),
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

  private def runnerEnvTask = Task.Anon {
    processEnvTask() ++ Map(
      "PYTHONPATH" -> transitivePythonPath().map(_.path).mkString(java.io.File.pathSeparator),
      "PYTHONPYCACHEPREFIX" -> (Task.dest / "cache").toString,
      "VIRTUAL_ENV" -> venv().path.toString,
      "PATH" -> (
        venvBin().path.toString + java.io.File.pathSeparator +
          PythonModule.pathEnvironmentValue(Task.env)
      )
    )
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
        "--python", pythonExe().path,
        transitivePythonPath().filter(path => os.exists(path.path)).flatMap(path =>
          Seq("--extra-search-path", path.path.toString)
        ),
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
        mainScript().path,
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
        env = runnerEnvTask() ++ forkEnv(),
        mainArgs = backgroundPaths.toArgs ++ Seq(
          "<subprocess>",
          pythonExe().path.toString
        ) ++ pythonOptions().toStringSeq ++ Seq(mainScript().path.toString) ++ args.value,
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
      transitiveUnmanagedWheels().map(_.path.toString) ++
      projectWheels.map(_.toString)
    val requirementFiles = transitivePythonRequirementFiles().filter { path =>
      os.exists(path.path) && os.read.lines(path.path).exists { line =>
        val trimmed = line.trim
        trimmed.nonEmpty && !trimmed.startsWith("#")
      }
    }
    val venvRepositoryArgs =
      if (requirements.nonEmpty || requirementFiles.nonEmpty)
        Seq("--venv-repository", venv().path.toString)
      else Nil
    uvRunner().run(
      (
        // format: off
        "tool", "run",
        uvIndexArgs(),
        "--from", pexTool(),
        "pex",
        requirements,
        requirementFiles.flatMap(path => Seq("-r", path.path.toString)),
        venvRepositoryArgs,
        transitivePythonPath().flatMap(pr =>
          Seq("-D", pr.path.toString)
        ),
        "--exe", mainScript().path,
        "--scie", "eager",
        "--scie-python-version", sciePythonVersion,
        "--scie-only",
        "-o", bundleFile,
        bundleOptions().toStringSeq
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
      os.call(
        cmd = Seq(Option(command).getOrElse(command0)) ++ options ++ args.value,
        env = env0 ++ Option(env).getOrElse(Map.empty),
        cwd = Option(workingDir).getOrElse(workingDir0),
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = true
      )
    }
  }
}
