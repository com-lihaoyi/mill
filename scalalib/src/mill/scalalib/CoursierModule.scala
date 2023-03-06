package mill.scalalib

import coursier.cache.FileCache
import coursier.{Dependency, Repository, Resolve}
import coursier.core.Resolution
import mill.{Agg, T}
import mill.define.Task
import mill.api.PathRef

import scala.annotation.nowarn

/**
 * This module provides the capability to resolve (transitive) dependencies from (remote) repositories.
 *
 * It's mainly used in [[JavaModule]], but can also be used stand-alone,
 * in which case you must provide repositories by overriding [[CoursierModule.repositoriesTask]].
 */
trait CoursierModule extends mill.Module {

  /**
   * Bind a dependency ([[Dep]]) to the actual module contetxt (e.g. the scala version and the platform suffix)
   * @return The [[BoundDep]]
   */
  def bindDependency: Task[Dep => BoundDep] = T.task { dep: Dep =>
    BoundDep((resolveCoursierDependency(): @nowarn).apply(dep), dep.force)
  }

  @deprecated("To be replaced by bindDependency", "Mill after 0.11.0-M0")
  def resolveCoursierDependency: Task[Dep => coursier.Dependency] = T.task {
    Lib.depToDependencyJava(_: Dep)
  }

  /**
   * Task that resolves the given dependencies using the repositories defined with [[repositoriesTask]].
   *
   * @param deps    The dependencies to resolve.
   * @param sources If `true`, resolve source dependencies instead of binary dependencies (JARs).
   * @return The [[PathRef]]s to the resolved files.
   */
  def resolveDeps(deps: Task[Agg[BoundDep]], sources: Boolean = false): Task[Agg[PathRef]] =
    T.task {
      Lib.resolveDependencies(
        repositories = repositoriesTask(),
        deps = deps(),
        sources = sources,
        mapDependencies = Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(implicitly[mill.api.Ctx.Log])
      )
    }

  /**
   * Map dependencies before resolving them.
   * Override this to customize the set of dependencies.
   */
  def mapDependencies: Task[Dependency => Dependency] = T.task { d: Dependency => d }

  /**
   * The repositories used to resolved dependencies with [[resolveDeps()]].
   */
  def repositoriesTask: Task[Seq[Repository]] = T.task {
    Resolve.defaultRepositories
  }

  /**
   * Customize the coursier resolution resolution process.
   * This is rarely needed to changed, as the default try to provide a
   * highly reproducible resolution process. But sometime, you need
   * more control, e.g. you want to add some OS or JDK specific resolution properties
   * which are sometimes used by Maven and therefore found in dependency artifact metadata.
   * For example, the JavaFX artifacts are known to use OS specific properties.
   * To fix resolution for JavaFX, you could override this task like the following:
   * {{{
   *     override def resolutionCustomizer = T.task {
   *       Some( (r: coursier.core.Resolution) =>
   *         r.withOsInfo(coursier.core.Activation.Os.fromProperties(sys.props.toMap))
   *       )
   *     }
   * }}}
   * @return
   */
  def resolutionCustomizer: Task[Option[Resolution => Resolution]] = T.task { None }

  /**
   * Customize the coursier file cache.
   *
   * This is rarely needed to be changed, but sometimes e.g you want to load a coursier plugin.
   * Doing so requires adding to coursier's classpath. To do this you could use the following:
   * {{{
   *   override def coursierCacheCustomizer = T.task {
   *      Some( (fc: coursier.cache.FileCache[Task]) =>
   *        fc.withClassLoaders(Seq(classOf[coursier.cache.protocol.S3Handler].getClassLoader))
   *      )
   *   }
   * }}}
   * @return
   */
  def coursierCacheCustomizer
      : Task[Option[FileCache[coursier.util.Task] => FileCache[coursier.util.Task]]] =
    T.task { None }

}
