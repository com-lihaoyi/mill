package mill.scalalib

import coursier.cache.FileCache
import coursier.params.ResolutionParams
import coursier.{Dependency, Module, Repository, Resolve, Type}
import coursier.core.{DependencyManagement, Resolution}
import mill.define.Task
import mill.api.PathRef

import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import mill.Agg

/**
 * This module provides the capability to resolve (transitive) dependencies from (remote) repositories.
 *
 * It's mainly used in [[JavaModule]], but can also be used stand-alone,
 * in which case you must provide repositories by overriding [[CoursierModule.repositoriesTask]].
 */
trait CoursierModule extends mill.Module {

  /**
   * Bind a dependency ([[Dep]]) to the actual module context (e.g. the scala version and the platform suffix)
   * @return The [[BoundDep]]
   */
  def bindDependency: Task[Dep => BoundDep] = Task.Anon { (dep: Dep) =>
    BoundDep((resolveCoursierDependency(): @nowarn).apply(dep), dep.force)
  }

  @deprecated("To be replaced by bindDependency", "Mill after 0.11.0-M0")
  def resolveCoursierDependency: Task[Dep => coursier.Dependency] = Task.Anon {
    Lib.depToDependencyJava(_: Dep)
  }

  def defaultResolver: Task[CoursierModule.Resolver] = Task.Anon {
    new CoursierModule.Resolver(
      repositories = repositoriesTask(),
      bind = bindDependency(),
      mapDependencies = Some(mapDependencies()),
      customizer = resolutionCustomizer(),
      coursierCacheCustomizer = coursierCacheCustomizer(),
      ctx = Some(implicitly[mill.api.Ctx.Log]),
      resolutionParams = resolutionParams()
    )
  }

  /**
   * Task that resolves the given dependencies using the repositories defined with [[repositoriesTask]].
   *
   * @param deps    The dependencies to resolve.
   * @param sources If `true`, resolve source dependencies instead of binary dependencies (JARs).
   * @param artifactTypes If non-empty, pull the passed artifact types rather than the default ones from coursier
   * @return The [[PathRef]]s to the resolved files.
   */
  def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean = false,
      artifactTypes: Option[Set[Type]] = None,
      bomDeps: Task[Agg[(Module, String)]] = Task.Anon(Agg.empty[(Module, String)])
  ): Task[Agg[PathRef]] =
    Task.Anon {
      Lib.resolveDependencies(
        repositories = repositoriesTask(),
        deps = deps(),
        sources = sources,
        artifactTypes = artifactTypes,
        mapDependencies = Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(implicitly[mill.api.Ctx.Log]),
        bomDeps = bomDeps()
      )
    }

  // bin-compat shim
  def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean,
      artifactTypes: Option[Set[Type]]
  ): Task[Agg[PathRef]] =
    resolveDeps(deps, sources, artifactTypes, Task.Anon(Agg.empty[(Module, String)]))

  @deprecated("Use the override accepting artifactTypes", "Mill after 0.12.0-RC3")
  def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean
  ): Task[Agg[PathRef]] =
    resolveDeps(deps, sources, None, Task.Anon(Agg.empty[(Module, String)]))

  /**
   * Map dependencies before resolving them.
   * Override this to customize the set of dependencies.
   */
  def mapDependencies: Task[Dependency => Dependency] = Task.Anon { (d: Dependency) => d }

  /**
   * The repositories used to resolved dependencies with [[resolveDeps()]].
   */
  def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
    val resolve = Resolve()
    val repos = Await.result(
      resolve.finalRepositories.future()(resolve.cache.ec),
      Duration.Inf
    )
    repos
  }

  /**
   * Customize the coursier resolution process.
   * This is rarely needed to changed, as the default try to provide a
   * highly reproducible resolution process. But sometime, you need
   * more control, e.g. you want to add some OS or JDK specific resolution properties
   * which are sometimes used by Maven and therefore found in dependency artifact metadata.
   * For example, the JavaFX artifacts are known to use OS specific properties.
   * To fix resolution for JavaFX, you could override this task like the following:
   * {{{
   *     override def resolutionCustomizer = Task.Anon {
   *       Some( (r: coursier.core.Resolution) =>
   *         r.withOsInfo(coursier.core.Activation.Os.fromProperties(sys.props.toMap))
   *       )
   *     }
   * }}}
   * @return
   */
  def resolutionCustomizer: Task[Option[Resolution => Resolution]] = Task.Anon { None }

  /**
   * Customize the coursier file cache.
   *
   * This is rarely needed to be changed, but sometimes e.g. you want to load a coursier plugin.
   * Doing so requires adding to coursier's classpath. To do this you could use the following:
   * {{{
   *   override def coursierCacheCustomizer = Task.Anon {
   *      Some( (fc: coursier.cache.FileCache[Task]) =>
   *        fc.withClassLoaders(Seq(classOf[coursier.cache.protocol.S3Handler].getClassLoader))
   *      )
   *   }
   * }}}
   * @return
   */
  def coursierCacheCustomizer
      : Task[Option[FileCache[coursier.util.Task] => FileCache[coursier.util.Task]]] =
    Task.Anon { None }

  /**
   * Resolution parameters, allowing to customize resolution internals
   *
   * This rarely needs to be changed. This allows to disable the new way coursier handles
   * BOMs since coursier 2.1.17 (used in Mill since 0.12.3) for example, with:
   * {{{
   *   def resolutionParams = super.resolutionParams()
   *     .withEnableDependencyOverrides(Some(false))
   * }}}
   *
   * Note that versions forced with `Dep#forceVersion()` take over forced versions manually
   * set in `resolutionParams`. The former should be favored to force versions in dependency
   * resolution.
   *
   * The Scala version set via `ScalaModule#scalaVersion` also takes over any Scala version
   * provided via `ResolutionParams#scalaVersionOpt`.
   */
  def resolutionParams: Task[ResolutionParams] = Task.Anon {
    ResolutionParams()
  }

}
object CoursierModule {

  class Resolver(
      repositories: Seq[Repository],
      bind: Dep => BoundDep,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      ctx: Option[mill.api.Ctx.Log] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[coursier.util.Task] => coursier.cache.FileCache[coursier.util.Task]
      ] = None,
      resolutionParams: ResolutionParams = ResolutionParams()
  ) {

    // bin-compat shim
    def this(
        repositories: Seq[Repository],
        bind: Dep => BoundDep,
        mapDependencies: Option[Dependency => Dependency],
        customizer: Option[coursier.core.Resolution => coursier.core.Resolution],
        ctx: Option[mill.api.Ctx.Log],
        coursierCacheCustomizer: Option[
          coursier.cache.FileCache[coursier.util.Task] => coursier.cache.FileCache[
            coursier.util.Task
          ]
        ]
    ) =
      this(
        repositories,
        bind,
        mapDependencies,
        customizer,
        ctx,
        coursierCacheCustomizer,
        ResolutionParams()
      )

    def resolveDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean = false,
        artifactTypes: Option[Set[coursier.Type]] = None,
        bomDeps: IterableOnce[(Module, String)] = Nil
    ): Agg[PathRef] = {
      Lib.resolveDependencies(
        repositories = repositories,
        deps = deps.map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind)),
        sources = sources,
        artifactTypes = artifactTypes,
        mapDependencies = mapDependencies,
        customizer = customizer,
        coursierCacheCustomizer = coursierCacheCustomizer,
        ctx = ctx,
        resolutionParams = resolutionParams,
        bomDeps = bomDeps
      ).getOrThrow
    }

    // bin-compat shim
    def resolveDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean,
        artifactTypes: Option[Set[coursier.Type]]
    ): Agg[PathRef] =
      resolveDeps(deps, sources, artifactTypes, Nil)

    @deprecated("Use the override accepting artifactTypes", "Mill after 0.12.0-RC3")
    def resolveDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean
    ): Agg[PathRef] =
      resolveDeps(deps, sources, None, Nil)

    /**
     * Processes dependencies and BOMs with coursier
     *
     * This makes coursier read and process BOM dependencies, and fill version placeholders
     * in dependencies with the BOMs.
     *
     * Note that this doesn't throw when a version placeholder cannot be filled, and just leaves
     * the placeholder behind.
     *
     * @param deps dependencies that might have placeholder versions ("_" as version)
     * @param resolutionParams coursier resolution parameters
     * @param bomDeps dependencies to read Bill-Of-Materials from
     * @return dependencies with version placeholder filled and data read from the BOM dependencies
     */
    def processDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        resolutionParams: ResolutionParams = ResolutionParams(),
        bomDeps: IterableOnce[(Module, String)] = Nil
    ): (Seq[Dependency], DependencyManagement.Map) = {
      val deps0 = deps
        .map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind))
        .filter(dep => mill.util.Jvm.isLocalTestDep(dep.dep).isEmpty)
      val res = Lib.resolveDependenciesMetadataSafe(
        repositories = repositories,
        deps = deps0,
        mapDependencies = mapDependencies,
        customizer = customizer,
        coursierCacheCustomizer = coursierCacheCustomizer,
        ctx = ctx,
        resolutionParams = resolutionParams,
        bomDeps = bomDeps
      ).getOrThrow
      (res.processedRootDependencies, res.bomDepMgmt)
    }
  }

  sealed trait Resolvable[T] {
    def bind(t: T, bind: Dep => BoundDep): BoundDep
  }
  implicit case object ResolvableDep extends Resolvable[Dep] {
    def bind(t: Dep, bind: Dep => BoundDep): BoundDep = bind(t)
  }
  implicit case object ResolvableBoundDep extends Resolvable[BoundDep] {
    def bind(t: BoundDep, bind: Dep => BoundDep): BoundDep = t
  }
}
