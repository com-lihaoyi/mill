package mill.mima

import mill.Agg
import mill.PathRef
import mill.T
import mill.api.Result
import mill.define.Task
import mill.scalalib._

import scala.util.chaining._

private[mima] trait ExtraCoursierSupport
  extends CoursierModule
    with ScalaModule {

  /** Resolves each dependency independently.
   *
   * @param deps
   *   The dependencies to resolve.
   * @param sources
   *   If `true`, resolved the source jars instead of the binary jars.
   * @return
   *   Tuples containing each dependencies and it's resolved transitive
   *   artifacts.
   */
  protected def resolveSeparateNonTransitiveDeps(
                                                  deps: Task[Agg[Dep]]
                                                ): Task[Agg[(Dep, Agg[PathRef])]] = T.task {
    val pRepositories = repositoriesTask()
    val pDeps = deps()
    val scalaV = scalaVersion()
    pDeps.map { dep =>
      val Result.Success(resolved) = Lib.resolveDependencies(
        repositories = pRepositories,
        deps = Agg(dep).map(dep =>
          Lib
            .depToBoundDep(dep, scalaV)
            .tap(dependency =>
              dependency.copy(dep = dependency.dep.withTransitive(false))
            )
        ),
        ctx = Some(implicitly[mill.api.Ctx.Log])
      )
      (dep, resolved)
    }
  }
}
