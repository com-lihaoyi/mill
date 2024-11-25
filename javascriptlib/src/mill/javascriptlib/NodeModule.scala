package mill.javascriptlib

import mill.{Module, PathRef, T, Task}

trait NodeModule extends Module {
  def moduleDeps: Seq[NodeModule] = Nil

  def npmDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def npmDevDeps: T[Seq[String]] = Task { Seq.empty[String] }

  def transitiveNpmDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDeps)().flatten
  }

  def transitiveNpmDevDeps: T[Seq[String]] = Task {
    Task.traverse(moduleDeps)(_.npmDevDeps)().flatten
  }

  def install = Task {
    PathRef(nodeModulesInModuleRoot().path)
  }

  def npmInstall = Task {
    val deps = npmDeps() ++ transitiveNpmDeps()
    if (deps.nonEmpty) {
      os.call((
        "npm",
        "install",
        deps
      ))
    }
    val devDeps = npmDevDeps() ++ transitiveNpmDevDeps()
    if (devDeps.nonEmpty) {
      os.call((
        "npm",
        "install",
        "--save-dev",
        devDeps
      ))
    }

    PathRef(Task.dest)
  }

  def nodeModulesInModuleRoot = Task {
    val installPath = npmInstall().path

    // Copy the node_modules to the source path so that IDE can find the types. Other tasks are able to depend on this.
    // Remove the old node_modules because this is a common thing to do in the JS world, also if the user did it manually, it won't repopulate if the task is cached.
    os.remove.all(millSourcePath / "node_modules")
    os.copy(installPath / "node_modules", millSourcePath / "node_modules", followLinks = false)
    PathRef(installPath)
  }
}
