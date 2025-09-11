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
   * Python dependencies declared in `requirements.txt` files. This is similar
   * to `pythonDeps`, but reads dependencies from a text file, allowing you to
   * reuse requirements files from existing projects.
   *
   * @see [[pythonDeps]]
   */
  def pythonRequirementFiles: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * requirements.txt of this module, and all other modules that this module
   * depends on, recursively.
   *
   * @see [[pythonRequirementFiles]]
   */
  def transitivePythonRequirementFiles: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitivePythonRequirementFiles)().flatten
    pythonRequirementFiles() ++ upstream
  }

  /**
   * Any python dependencies for development tools you want to add to this module.
   *
   * These dependencies are similar to `pythonDeps`, but will not be required to install this
   * module, only to work on it. For example, type checkers, linters, and bundlers should be
   * declared here.
   *
   * @see [[pythonDeps]]
   */
  def pythonToolDeps: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Any python wheels to install directly.
   *
   * Note: you can also include wheels by using [direct
   * references](https://peps.python.org/pep-0440/#direct-references) in [[pythonRequirementFiles]], for
   * example `"pip @ file:///localbuilds/pip-1.3.1-py33-none-any.whl"`. However, if you do that then
   * changes to these files won't get picked up and you are on the hook for cache invalidation.
   * Therefore, if you have any wheels that you wish to install directly, it is recommended to add
   * them here.
   */
  def unmanagedWheels: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * Any python wheels to install directly, for this module and all upstream modules, recursively.
   *
   * @see [[unmanagedWheels]]
   */
  def transitiveUnmanagedWheels: T[Seq[PathRef]] = Task {
    val upstream = Task.traverse(moduleDeps)(_.transitiveUnmanagedWheels)().flatten
    unmanagedWheels() ++ upstream
  }

  /**
   * Base URLs of the Python Package Indexes to search for packages. Defaults to
   * https://pypi.org/simple.
   *
   * These should point to repositories compliant with PEP 503 (the simple
   * repository API) or local directories laid out in the same format.
   */
  def indexes: T[Seq[String]] = Task {
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
   * @see [[unmanagedWheels]]
   * @see [[pythonToolDeps]]
   */
  def pipInstallArgs: T[PipModule.InstallArgs] = Task {
    val indexArgs: Seq[String] = indexes().toList match {
      case Nil => Seq("--no-index")
      case head :: Nil => Seq("--index-url", head)
      case head :: tail =>
        Seq("--index-url", head) ++ tail.flatMap(t => Seq("--extra-index-url", t))
    }

    PipModule.InstallArgs(
      indexArgs ++
        transitiveUnmanagedWheels().map(_.path.toString) ++
        pythonToolDeps() ++
        transitivePythonDeps() ++
        transitivePythonRequirementFiles().flatMap(pr =>
          Seq("-r", pr.path.toString)
        ),
      transitiveUnmanagedWheels() ++ transitivePythonRequirementFiles()
    )
  }

}

object PipModule {

  /**
   * A list of string arguments, with a cache-busting signature for any strings which represent
   * files.
   */
  case class InstallArgs(
      args: Seq[String],
      sig: Int
  )
  object InstallArgs {
    implicit val rw: upickle.ReadWriter[InstallArgs] = upickle.macroRW
    def apply(
        args: Seq[String],
        paths: Seq[PathRef]
    ): InstallArgs = {
      val hash = java.security.MessageDigest.getInstance("MD5")
      for (arg <- args) {
        hash.update(arg.getBytes("utf-8"))
      }
      for (path <- paths) {
        hash.update((path.sig >> 24).toByte)
        hash.update((path.sig >> 16).toByte)
        hash.update((path.sig >> 8).toByte)
        hash.update((path.sig).toByte)
      }

      InstallArgs(args.toSeq, java.util.Arrays.hashCode(hash.digest()))
    }
  }

}
