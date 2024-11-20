package mill.pythonlib

import mill._
import mill.api.Result
import mill.util.Util
import mill.util.Jvm
import mill.api.Ctx

trait PythonModule extends Module with TaskModule { outer =>

  /**
   *  The direct dependencies of this module.
   *  This is meant to be overridden to add dependencies.
   */
  def moduleDeps: Seq[PythonModule] = Nil

  /**
   * The folders where the source files for this mill module live.
   *
   * Python modules will be defined relative to these directories.
   */
  def sources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "src" }

  /**
   * The script to run. This file may not exist if this module is only a library.
   */
  def script: T[PathRef] = Task.Source { millSourcePath / "src" / "main.py" }

  /**
   * Any python dependencies you want to add to this module. The format of each
   * dependency should be the same as used with `pip install`, or as you would
   * find in a `requirements.txt` file. E.g. `def pythonDeps =
   * Seq("numpy==2.1.3")`
   */
  def pythonDeps: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Python dependencies of this module, and all other modules that this module
   * depends on, recursively.
   */
  def transitivePythonDeps: T[Seq[String]] = Task {
    val upstreamDependencies = Task.traverse(moduleDeps)(_.transitivePythonDeps)().flatten
    pythonDeps() ++ upstreamDependencies
  }

  /**
   * Source directories of this module, and all other modules that this module
   * depends on, recursively.
   */
  def transitiveSources: T[Seq[PathRef]] = Task {
    val upstreamSources = Task.traverse(moduleDeps)(_.transitiveSources)().flatten
    sources() ++ upstreamSources
  }

  /**
   * An executable python interpreter. This interpreter is set up to run in a
   * virtual environment which has been initialized to contain all libraries and
   * tools needed by this module and its dependencies.
   */
  def pythonExe: T[PathRef] = Task {
    os.call(("python3", "-m", "venv", Task.dest / "venv"))
    val python = Task.dest / "venv" / "bin" / "python3"
    os.call((python, "-m", "pip", "install", "mypy==1.13.0", "pex==2.24.1", transitivePythonDeps()))

    PathRef(python)
  }

  // TODO: right now, any task that calls this helper will have its own python
  // cache. This is slow. Look into sharing the cache between tasks.
  def runner: Task[PythonModule.Runner] = Task.Anon {
    new PythonModule.RunnerImpl(
      command0 = pythonExe().path.toString,
      env0 = Map(
        "PYTHONPATH" -> transitiveSources().map(_.path).mkString(java.io.File.pathSeparator),
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
   * Run the main python script of this module.
   *
   * @see [[script]]
   */
  def run(args: mill.define.Args) = Task.Command {
    runner().run(
      (
        script().path,
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
        transitivePythonDeps(),
        transitiveSources().flatMap(pr =>
          Seq("-D", pr.path.toString)
        ),
        "--exe", script().path,
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
