package mill.pythonlib

import mill._

/**
 * Basic tasks for preparing a python interpreter in a venv with required
 * dependencies installed.
 */
trait PipModule extends Module {

  /**
   * The direct dependencies of this module.
   * This is meant to be overridden to add dependencies.
   */
  def moduleDeps: Seq[PipModule] = Nil

  /**
   * Any python dependencies you want to add to this module. The format of each
   * dependency should be the same as used with `pip install`, or as you would
   * find in a `requirements.txt` file. E.g. `def pythonDeps =
   * Seq("numpy==2.1.3")`.
   *
   * Dependencies declared here will also be required when installing this module.
   */
  def pythonDeps: T[Agg[String]] = Task { Agg.empty[String] }

  /**
   * Python dependencies of this module, and all other modules that this module
   * depends on, recursively.
   */
  def transitivePythonDeps: T[Agg[String]] = Task {
    val upstreamDependencies = Task.traverse(moduleDeps)(_.transitivePythonDeps)().flatten
    pythonDeps() ++ upstreamDependencies
  }

  /**
   * Python dependencies declared in `requirements.txt` files. This is similar
   * to `pythonDeps`, but reads dependencies from a text file, allowing you to
   * reuse requirements files from existing projects.
   *
   * @see [[pythonDeps]]
   */
  def pythonRequirements: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * requirements.txt of this module, and all other modules that this module
   * depends on, recursively.
   *
   * @see pythonRequirements
   */
  def transitivePythonRequirements: T[Agg[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitivePythonRequirements)().flatten
    pythonRequirements() ++ upstream
  }

  /**
   * Any python dependencies for development you want to add to this module.
   *
   * These dependencies are similar to `pythonDeps`, but will not be required to
   * install this module, only to work on it. For example, type checkers,
   * linters, and bundlers should be declared here.
   *
   * @see [[pythonDeps]]
   */
  def pythonDevDeps: T[Agg[String]] = Task { Agg.empty[String] }

  /**
   * Python dependencies for development declared in `requirements.txt` files.
   *
   * @see [[pythonDevDeps]]
   */
  def pythonDevRequirements: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * Base URLs of the Python Package Indexes to search for packages. Defaults to
   * https://pypi.org/simple.
   *
   * These should point to repositories compliant with PEP 503 (the simple
   * repository API) or local directories laid out in the same format.
   */
  def indexes: T[Agg[String]] = Task {
    Seq("https://pypi.org/simple")
  }

  /**
   * Arguments to be passed to `pip install` when preparing the environment.
   *
   * This task is called by `pythonExe`. It serves as an escape hatch, should
   * you need to override it for some reason. Normally, you should not need to
   * edit this task directly. Instead, prefer editing the other tasks of this
   * module which influence how the arguments are created.
   *
   * @see [[indexes]]
   * @see [[pythonDeps]]
   * @see [[pythonDevDeps]]
   */
  def pipInstallArgs: T[Seq[String]] = Task {
    val indexArgs: Seq[String] = indexes().toList match {
      case Nil => Seq("--no-index")
      case head :: Nil => Seq("--index-url", head)
      case head :: tail =>
        Seq("--index-url", head) ++ tail.flatMap(t => Seq("--extra-index-url", t))
    }

    indexArgs ++
      pythonDevDeps() ++
      transitivePythonDeps() ++
      (pythonDevRequirements() ++ transitivePythonRequirements()).flatMap(pr =>
        Seq("-r", pr.path.toString)
      )
  }

}
