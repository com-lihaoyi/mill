package mill.scalalib

import coursier.cache.FileCache
import coursier.core.{BomDependency, DependencyManagement, Resolution}
import coursier.params.ResolutionParams
import coursier.{Dependency, Repository, Resolve, Type}
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
    BoundDep((resolveCoursierDependency.apply(): @nowarn).apply(dep), dep.force)
  }

  @deprecated("To be replaced by bindDependency", "Mill after 0.11.0-M0")
  def resolveCoursierDependency: Task[Dep => coursier.Dependency] = Task.Anon {
    Lib.depToDependencyJava(_: Dep)
  }

  /**
   * A `CoursierModule.Resolver` to resolve dependencies.
   *
   * Unlike `defaultResolver`, this resolver can resolve Mill internal modules
   * (obtained via `JavaModule#coursierDependency`).
   *
   * @return `CoursierModule.Resolver` instance
   */
  def internalResolver: Task[CoursierModule.Resolver] = Task.Anon {
    new CoursierModule.Resolver(
      repositories = allRepositoriesTask(),
      bind = bindDependency(),
      mapDependencies = Some(mapDependencies()),
      customizer = resolutionCustomizer(),
      coursierCacheCustomizer = coursierCacheCustomizer(),
      ctx = Some(implicitly[mill.api.Ctx.Log]),
      resolutionParams = resolutionParams()
    )
  }

  /**
   * A `CoursierModule.Resolver` to resolve dependencies.
   *
   * Can be used to resolve external dependencies, if you need to download an external
   * tool from Maven or Ivy repositories, by calling `CoursierModule.Resolver#resolveDeps`.
   *
   * @return `CoursierModule.Resolver` instance
   */
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
      enableMillInternalDependencies: Boolean = false
  ): Task[Agg[PathRef]] = {
    val repositoriesTask0 =
      if (enableMillInternalDependencies) allRepositoriesTask
      else repositoriesTask
    Task.Anon {
      Lib.resolveDependencies(
        repositories = repositoriesTask0(),
        deps = deps(),
        sources = sources,
        artifactTypes = artifactTypes,
        mapDependencies = Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
        ctx = Some(implicitly[mill.api.Ctx.Log])
      )
    }
  }

  // bin-compat shim
  def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean,
      artifactTypes: Option[Set[Type]]
  ): Task[Agg[PathRef]] =
    resolveDeps(
      deps,
      sources,
      artifactTypes,
      enableMillInternalDependencies = false
    )

  @deprecated("Use the override accepting artifactTypes", "Mill after 0.12.0-RC3")
  def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean
  ): Task[Agg[PathRef]] =
    resolveDeps(deps, sources, None)

  /**
   * Map dependencies before resolving them.
   * Override this to customize the set of dependencies.
   */
  def mapDependencies: Task[Dependency => Dependency] = Task.Anon { (d: Dependency) => d }

  /**
   * Mill internal repositories to be used during dependency resolution
   *
   * These are not meant to be modified by Mill users, unless you really know what you're
   * doing.
   */
  def internalRepositories: Task[Seq[Repository]] = Task.Anon(Nil)

  /**
   * The repositories used to resolved dependencies with [[resolveDeps()]].
   *
   * See [[allRepositoriesTask]] if you need to resolve Mill internal modules.
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
   * The repositories used to resolved dependencies
   *
   * Unlike [[repositoriesTask]], this includes the Mill internal repositories,
   * which allow to resolve Mill internal modules (usually brought in via
   * `JavaModule#coursierDependency`).
   *
   * Beware that this needs to evaluate `JavaModule#coursierProject` of all
   * module dependencies of the current module, which itself evaluates `JavaModule#ivyDeps`
   * and related tasks. You shouldn't depend on this task from implementations of `ivyDeps`,
   * which would introduce cycles between Mill tasks.
   */
  def allRepositoriesTask: Task[Seq[Repository]] = Task.Anon {
    internalRepositories() ++ repositoriesTask()
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
   *
   * The default configuration set in `ResolutionParams#defaultConfiguration` is ignored when
   * Mill fetches dependencies to be passed to the compiler (equivalent to Maven "compile scope").
   * In that case, it forces the default configuration to be "compile". On the other hand, when
   * fetching dependencies for runtime (equivalent to Maven "runtime scope"), the value in
   * `ResolutionParams#defaultConfiguration` is used.
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
        resolutionParamsMapOpt: Option[ResolutionParams => ResolutionParams] = None
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
        resolutionParams = resolutionParamsMapOpt.fold(resolutionParams)(_(resolutionParams))
      ).getOrThrow
    }

    // bin-compat shim
    def resolveDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean,
        artifactTypes: Option[Set[coursier.Type]]
    ): Agg[PathRef] =
      resolveDeps(deps, sources, artifactTypes, None)

    @deprecated("Use the override accepting artifactTypes", "Mill after 0.12.0-RC3")
    def resolveDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean
    ): Agg[PathRef] =
      resolveDeps(deps, sources, None)

    /**
     * Processes dependencies and BOMs with coursier
     *
     * This makes coursier read and process BOM dependencies, and fill empty versions
     * in dependencies with the BOMs.
     *
     * Note that this doesn't throw when an empty version cannot be filled, and just leaves
     * the empty version behind.
     *
     * @param deps dependencies that might have empty versions
     * @param resolutionParams coursier resolution parameters
     * @return dependencies with empty version filled
     */
    def processDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        resolutionParams: ResolutionParams = ResolutionParams(),
        boms: IterableOnce[BomDependency] = Nil
    ): (Seq[coursier.core.Dependency], DependencyManagement.Map) = {
      val deps0 = deps
        .map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind))
        .iterator.toSeq
      val boms0 = boms.toSeq
      val res = Lib.resolveDependenciesMetadataSafe(
        repositories = repositories,
        deps = deps0,
        mapDependencies = mapDependencies,
        customizer = customizer,
        coursierCacheCustomizer = coursierCacheCustomizer,
        ctx = ctx,
        resolutionParams = resolutionParams,
        boms = boms0
      ).getOrThrow

      (
        res.finalDependenciesCache.getOrElse(
          deps0.head.dep,
          sys.error(
            s"Should not happen - could not find root dependency ${deps0.head.dep} in Resolution#finalDependenciesCache"
          )
        ),
        res.projectCache
          .get(deps0.head.dep.moduleVersion)
          .map(_._2.overrides.flatten.toMap)
          .getOrElse {
            sys.error(
              s"Should not happen - could not find root dependency ${deps0.head.dep.moduleVersion} in Resolution#projectCache"
            )
          }
      )
    }

    /**
     * All dependencies pulled by the passed dependencies
     *
     * @param deps root dependencies
     * @return full - ordered - list of dependencies pulled by `deps`
     */
    def allDeps[T: CoursierModule.Resolvable](
        deps: IterableOnce[T]
    ): Seq[coursier.core.Dependency] = {
      val deps0 = deps
        .map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind))
        .iterator.toSeq
      val res = Lib.resolveDependenciesMetadataSafe(
        repositories = repositories,
        deps = deps0,
        mapDependencies = mapDependencies,
        customizer = customizer,
        coursierCacheCustomizer = coursierCacheCustomizer,
        ctx = ctx,
        resolutionParams = ResolutionParams(),
        boms = Nil
      ).getOrThrow

      res.orderedDependencies
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
  implicit case object ResolvableCoursierDep extends Resolvable[coursier.core.Dependency] {
    def bind(t: coursier.core.Dependency, bind: Dep => BoundDep): BoundDep =
      BoundDep(t, force = false)
  }
}
