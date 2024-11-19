package mill.pythonlib

import mill._
import mill.api.Result
import mill.util.Util
import mill.util.Jvm

trait PythonModule extends Module with TaskModule {
  def moduleDeps: Seq[PythonModule] = Nil

  /**
   * The folders where the source files for this mill module live
   *
   * Python modules will be defined relative to these directories.
   */
  def sources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "src" }

  /**
   * The script to run. This file may not exist if this module is only a library.
   */
  def script: T[PathRef] = Task.Source { millSourcePath / "src" / "main.py" }

  def pythonDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def transitivePythonDeps: T[Seq[String]] = Task {
    val upstreamDependencies = Task.traverse(moduleDeps)(_.transitivePythonDeps)().flatten
    pythonDeps() ++ upstreamDependencies
  }

  def transitiveSources: T[Seq[PathRef]] = Task {
    val upstreamSources = Task.traverse(moduleDeps)(_.transitiveSources)().flatten
    sources() ++ upstreamSources
  }

  def pythonExe: T[PathRef] = Task {
    os.call(("python3", "-m", "venv", Task.dest / "venv"))
    val python = Task.dest / "venv" / "bin" / "python3"
    os.call((python, "-m", "pip", "install", "mypy==1.13.0", "pex==2.24.1", transitivePythonDeps()))

    PathRef(python)
  }

  def typeCheck: T[Unit] = Task {
    Task.traverse(moduleDeps)(_.typeCheck)()

    os.call(
      (pythonExe().path, "-m", "mypy", "--strict", sources().map(_.path)),
      stdout = os.Inherit,
      cwd = T.workspace
    )
  }

  def run(args: mill.define.Args) = Task.Command {
    os.call(
      (pythonExe().path, script().path, args.value),
      env = Map(
        "PYTHONPATH" -> transitiveSources().map(_.path).mkString(":"),
        "PYTHONPYCACHEPREFIX" -> (T.dest / "cache").toString
      ),
      stdout = os.Inherit,
      cwd = T.dest
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
      Jvm.runSubprocess(
        Seq(pythonExe().path.toString),
        envArgs = Map(
          "PYTHONPATH" -> transitiveSources().map(_.path).mkString(":").toString,
          "PYTHONPYCACHEPREFIX" -> (T.dest / "cache").toString
        ),
        workingDir = Task.dest
      )
      Result.Success(())
    }
  }

  /** Bundles the project into a single PEX executable(bundle.pex). */
  def bundle = Task {
    val pexFile = Task.dest / "bundle.pex"
    os.call(
      (
        // format: off
        pythonExe().path,
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
      stdout = os.Inherit,
      cwd = T.dest
    )

    PathRef(pexFile)
  }

}
