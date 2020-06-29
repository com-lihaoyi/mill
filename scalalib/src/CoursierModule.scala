package mill.scalalib

import coursier.{Dependency, Repository, Resolve}
import mill.{Agg, T}
import mill.define.Task
import mill.eval.PathRef

/**
  * This module provides the capability to resolve (transitive) dependencies from (remote) repositories.
  *
  * It's mainly used in [[JavaModule]], but can also be used stand-alone,
  * in which case you must provide repositories by overriding [[CoursierModule.repositories]].
  */
trait CoursierModule extends mill.Module {

  def resolveCoursierDependency: Task[Dep => coursier.Dependency] = T.task {
    Lib.depToDependencyJava(_: Dep)
  }

  /**
    * Task that resolves the given dependencies using the repositories defined with [[repositories]].
    *
    * @param deps    The dependencies to resolve.
    * @param sources If `true`, resolve source dependencies instead of binary dependencies (JARs).
    * @return The [[PathRef]]s to the resolved files.
    */
  def resolveDeps(deps: Task[Agg[Dep]], sources: Boolean = false): Task[Agg[PathRef]] = T.task {
    Lib.resolveDependencies(
      repositories,
      resolveCoursierDependency().apply(_),
      deps(),
      sources,
      mapDependencies = Some(mapDependencies()),
      Some(implicitly[mill.util.Ctx.Log])
    )
  }

  /**
    * Map dependencies before resolving them.
    * Override this to customize the set of dependencies.
    */
  def mapDependencies: Task[Dependency => Dependency] = T.task { d: coursier.Dependency => d }

  /**
    * The repositories used to resolved dependencies with [[resolveDeps()]].
    */
  def repositories: Seq[Repository] = Resolve.defaultRepositories

}
