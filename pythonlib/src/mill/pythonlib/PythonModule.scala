package mill.pythonlib
import mill._

trait PythonModule extends Module {
  def moduleDeps: Seq[PythonModule] = Nil
  def mainFileName: T[String] = Task { "main.py" }
  def sources: T[PathRef] = Task.Source(millSourcePath / "src")
  def resources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "resources" }

  def allSourceFiles: T[Seq[PathRef]] = Task {
    os.walk(sources().path).filter(_.ext == "py").map(PathRef(_))
  }

  def pythonDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def transitivePythonDeps: T[Seq[String]] = Task {
    val upstreamDependencies = Task.traverse(moduleDeps)(_.transitivePythonDeps)().flatten
    pythonDeps() ++ upstreamDependencies
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
      (pythonExe().path, "-m", "mypy", "--strict", sources().path),
      stdout = os.Inherit,
      cwd = T.workspace
    )
  }

  def gatherScripts(upstream: Seq[(PathRef, PythonModule)]) = {
    for ((sourcesFolder, mod) <- upstream) {
      val destinationPath =
        os.pwd / mod.millSourcePath.subRelativeTo(mill.api.WorkspaceRoot.workspaceRoot)
      os.copy.over(sourcesFolder.path / os.up, destinationPath)
    }
  }

  def run(args: mill.define.Args) = Task.Command {
    gatherScripts(Task.traverse(moduleDeps)(_.sources)().zip(moduleDeps))

    os.call(
      (pythonExe().path, sources().path / mainFileName(), args.value),
      env = Map(
        "PYTHONPATH" -> Task.dest.toString,
        "SOURCES" -> allSourceFiles().map(_.toString).mkString(":"),
        "RESOURCES" -> resources().map(_.toString).mkString(":")
      ),
      stdout = os.Inherit
    )
  }

  /** Bundles the project into a single PEX executable(bundle.pex). */
  def bundle = Task {
    gatherScripts(Task.traverse(moduleDeps)(_.sources)().zip(moduleDeps))

    val pexFile = Task.dest / "bundle.pex"
    os.call(
      (
        pythonExe().path,
        "-m",
        "pex",
        transitivePythonDeps(),
        "-D",
        Task.dest,
        "-c",
        sources().path / mainFileName(),
        "-o",
        pexFile,
        "--scie",
        "eager"
      ),
      env = Map(
        "PYTHONPATH" -> Task.dest.toString,
        "SOURCES" -> allSourceFiles().map(_.toString).mkString(":"),
        "RESOURCES" -> resources().map(_.toString).mkString(":")
      ),
      stdout = os.Inherit
    )

    PathRef(pexFile)
  }

  def pythonRepl(args: mill.define.Args) = Task.Command {
    gatherScripts(Task.traverse(moduleDeps)(_.sources)().zip(moduleDeps))

    os.call(
      (pythonExe().path, "-i", sources().path / mainFileName()),
      env = Map(
        "PYTHONPATH" -> Task.dest.toString,
        "SOURCES" -> allSourceFiles().map(_.toString).mkString(":"),
        "RESOURCES" -> resources().map(_.toString).mkString(":")
      ),
      stdin = args.value,
      stdout = os.Inherit
    )
  }

  trait PythonTests extends PythonModule {

    def pythonUnitTests: T[Unit] = Task {
      gatherScripts(Task.traverse(moduleDeps)(_.sources)().zip(moduleDeps))

      os.call(
        (pythonExe().path, sources().path / mainFileName(), "-v"),
        env = Map(
          "PYTHONPATH" -> Task.dest.toString,
          "SOURCES" -> allSourceFiles().map(_.toString).mkString(":"),
          "RESOURCES" -> resources().map(_.toString).mkString(":")
        ),
        stdout = os.Inherit
      )
    }

  }
}
