package mill.pythonlib

import mill._
import mill.api.Result
import mill.util.{Jvm}
import mill.api.Ctx
import mill.constants.ServerFiles

trait PythonModule extends PipModule with TaskModule { outer =>

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

  /**
   * An executable python interpreter. This interpreter is set up to run in a
   * virtual environment which has been initialized to contain all libraries and
   * tools needed by this module and its dependencies.
   */
  def pythonExe: T[PathRef] = Task {
    os.call((hostPythonCommand(), "-m", "venv", Task.dest / "venv"))
    val python = Task.dest / "venv/bin/python3"
    os.call((python, "-m", "pip", "install", pipInstallArgs().args), stdout = os.Inherit)
    PathRef(python)
  }

  /**
   * The folders where the source files for this mill module live.
   *
   * Python modules will be defined relative to these directories.
   */
  def sources: T[Seq[PathRef]] = Task.Sources { "src" }

  /**
   * The folders where the resource files for this module live.
   */
  def resources: T[Seq[PathRef]] = Task.Sources { "resources" }

  /**
   * The python script to run. This file may not exist if this module is only a library.
   */
  def mainScript: T[PathRef] = Task.Source { "src/main.py" }

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
   * handwritten; these files can be generated in this target itself,
   * or can refer to files generated from other targets
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
  def bundleOptions: T[Seq[String]] = Task { Seq("--scie", "eager") }

  // TODO: right now, any task that calls this helper will have its own python
  // cache. This is slow. Look into sharing the cache between tasks.
  def runner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      command0 = pythonExe().path.toString,
      options = pythonOptions(),
      env0 = runnerEnvTask() ++ forkEnv(),
      workingDir0 = Task.dest
    )
  }

  private def runnerEnvTask = Task.Anon {
    Map(
      "PYTHONPATH" -> transitivePythonPath().map(_.path).mkString(java.io.File.pathSeparator),
      "PYTHONPYCACHEPREFIX" -> (Task.dest / "cache").toString,
      if (Task.log.prompt.colored) { "FORCE_COLOR" -> "1" }
      else { "NO_COLOR" -> "1" }
    )
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
        "--cache-dir", (Task.dest / "mypycache").toString,
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
  def run(args: mill.define.Args) = Task.Command {
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
  def runBackground(args: mill.define.Args) = Task.Command {
    val (procUuidPath, procLockfile, procUuid) = mill.scalalib.RunModule.backgroundSetup(Task.dest)
    val pwd0 = os.Path(java.nio.file.Paths.get(".").toAbsolutePath)

    os.checker.withValue(os.Checker.Nop) {
      Jvm.spawnProcess(
        mainClass = "mill.scalalib.backgroundwrapper.MillBackgroundWrapper",
        classPath = mill.scalalib.JvmWorkerModule.backgroundWrapperClasspath().map(_.path).toSeq,
        jvmArgs = Nil,
        env = runnerEnvTask(),
        mainArgs = Seq(
          procUuidPath.toString,
          procLockfile.toString,
          procUuid,
          "500",
          "<subprocess>",
          pythonExe().path.toString,
          mainScript().path.toString
        ) ++ args.value,
        cwd = Task.workspace,
        stdin = "",
        // Hack to forward the background subprocess output to the Mill server process
        // stdout/stderr files, so the output will get properly slurped up by the Mill server
        // and shown to any connected Mill client even if the current command has completed
        stdout = os.PathAppendRedirect(pwd0 / ".." / ServerFiles.stdout),
        stderr = os.PathAppendRedirect(pwd0 / ".." / ServerFiles.stderr),
        javaHome = mill.scalalib.JvmWorkerModule.javaHome().map(_.path)
      )
    }
    ()
  }

  override def defaultCommandName(): String = "run"

  /**
   * Opens up a Python console with your module and all dependencies present,
   * for you to test and operate your code interactively.
   */
  def console(): Command[Unit] = Task.Command(exclusive = true) {
    if (!mill.constants.Util.hasConsole()) {
      Result.Failure("console needs to be run with the -i/--interactive flag")
    } else {
      runner().run()
      Result.Success(())
    }
  }

  /** Bundles the project into a single PEX executable(bundle.pex). */
  def bundle = Task {
    val pexFile = Task.dest / "bundle.pex"
    runner().run(
      (
        // format: off
        "-m", "pex",
        transitivePythonDeps().toSeq,
        transitivePythonPath().flatMap(pr =>
          Seq("-D", pr.path.toString)
        ),
        "--exe", mainScript().path,
        "-o", pexFile,
        bundleOptions()
        // format: on
      ),
      workingDir = Task.dest
    )
    PathRef(pexFile)
  }

  trait PythonTests extends PythonModule {
    override def moduleDeps: Seq[PythonModule] = Seq(outer)
  }

}

object PythonModule {
  trait Runner {
    def run(
        args: os.Shellable = Seq(),
        command: String = null,
        env: Map[String, String] = null,
        workingDir: os.Path = null
    )(implicit ctx: Ctx): Unit
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
    )(implicit ctx: Ctx): Unit = {
      os.call(
        cmd = Seq(Option(command).getOrElse(command0)) ++ options ++ args.value,
        env = Option(env).getOrElse(env0),
        cwd = Option(workingDir).getOrElse(workingDir0),
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = true
      )
    }
  }
}
