package mill.pythonlib

import mill._
import mill.api.Result
import mill.util.Util
import mill.util.Jvm
import mill.api.Ctx

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
  def hostPythonCommand: T[String] = T { "python3" }

  /**
   * An executable python interpreter. This interpreter is set up to run in a
   * virtual environment which has been initialized to contain all libraries and
   * tools needed by this module and its dependencies.
   */
  def pythonExe: T[PathRef] = Task {
    os.call((hostPythonCommand(), "-m", "venv", Task.dest / "venv"))
    val python = Task.dest / "venv" / "bin" / "python3"
    os.call((python, "-m", "pip", "install", pipInstallArgs()), stdout = os.Inherit)
    PathRef(python)
  }

  /**
   * The folders where the source files for this mill module live.
   *
   * Python modules will be defined relative to these directories.
   */
  def sources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "src" }

  /**
   * The folders where the resource files for this module live.
   */
  def resources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "resources" }

  /**
   * The mainScript to run. This file may not exist if this module is only a library.
   */
  def mainScript: T[PathRef] = Task.Source { millSourcePath / "src" / "main.py" }

  override def pythonDevDeps: T[Agg[String]] = Task {
    super.pythonDevDeps() ++ Agg("mypy==1.13.0", "pex==2.24.1")
  }

  /**
   * Additional directories to include in the PYTHONPATH directly. These paths
   * are "unmanaged": they'll be included as they are on disk.
   */
  def unmanagedPythonPath: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * Source directories of this module, and all other modules that this module
   * depends on, recursively.
   */
  def transitiveSources: T[Seq[PathRef]] = Task {
    val upstreamSources = Task.traverse(moduleDeps)(_.transitiveSources)().flatten
    sources() ++ upstreamSources
  }

  /**
   * The directories used to construct the PYTHONPATH for this module, used for
   * execution, excluding upstream modules.
   *
   * This includes source directories, resources and other unmanaged
   * directories.
   */
  def localPythonPath: T[Seq[PathRef]] = Task {
    transitiveSources() ++ unmanagedPythonPath() ++ resources()
  }

  /**
   * The transitive version of [[localPythonPath]]: this includes the
   * directories of all upstream modules as well.
   */
  def transitivePythonPath: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitivePythonPath)().flatten
    localPythonPath() ++ upstream
  }

  // TODO: right now, any task that calls this helper will have its own python
  // cache. This is slow. Look into sharing the cache between tasks.
  def runner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      command0 = pythonExe().path.toString,
      env0 = Map(
        "PYTHONPATH" -> transitivePythonPath().map(_.path).mkString(java.io.File.pathSeparator),
        "PYTHONPYCACHEPREFIX" -> (T.dest / "cache").toString,
        if (Task.log.colored) { "FORCE_COLOR" -> "1" }
        else { "NO_COLOR" -> "1" }
      ),
      workingDir0 = Task.workspace
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
        "--cache-dir", (T.dest / "mypycache").toString,
        sources().map(_.path)
        // format: on
      )
    )
  }

  /**
   * Run the main python mainScript of this module.
   *
   * @see [[mainScript]]
   */
  def run(args: mill.define.Args) = Task.Command {
    runner().run(
      (
        mainScript().path,
        args.value
      )
    )
  }

  override def defaultCommandName(): String = "run"

  /**
   * Opens up a Python console with your module and all dependencies present,
   * for you to test and operate your code interactively.
   */
  def console(): Command[Unit] = Task.Command(exclusive = true) {
    if (!Util.isInteractive()) {
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
        "--scie", "eager",
        // format: on
      ),
      workingDir = T.dest
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
      env0: Map[String, String],
      workingDir0: os.Path
  ) extends Runner {
    def run(
        args: os.Shellable = Seq(),
        command: String = null,
        env: Map[String, String] = null,
        workingDir: os.Path = null
    )(implicit ctx: Ctx): Unit =
      Jvm.runSubprocess(
        commandArgs = Seq(Option(command).getOrElse(command0)) ++ args.value,
        envArgs = Option(env).getOrElse(env0),
        workingDir = Option(workingDir).getOrElse(workingDir0)
      )
  }
}
