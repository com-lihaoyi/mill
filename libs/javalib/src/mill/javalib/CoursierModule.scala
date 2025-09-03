package mill.javalib

import com.lihaoyi.unroll
import coursier.cache.FileCache
import coursier.core.Resolution
import coursier.core.VariantSelector.VariantMatcher
import coursier.params.ResolutionParams
import coursier.{Dependency, Repository}
import mill.api.{ModuleRef, PathRef, Result, Task}
import mill.util.Jvm
import mill.T

import scala.annotation.unused

/**
 * This module provides the capability to resolve (transitive) dependencies from (remote) repositories.
 *
 * It's mainly used in [[JavaModule]], but can also be used stand-alone,
 * in which case you must provide repositories by overriding [[CoursierModule.repositoriesTask]].
 */
trait CoursierModule extends mill.api.Module {

  /**
   * Whether to enable Gradle Module support when fetching dependencies
   */
  def checkGradleModules: T[Boolean] = false

  /**
   * Bind a dependency ([[Dep]]) to the actual module context (e.g. the scala version and the platform suffix)
   * @return The [[BoundDep]]
   */
  def bindDependency: Task[Dep => BoundDep] = Task.Anon { (dep: Dep) =>
    BoundDep(Lib.depToDependencyJava(dep), dep.force)
  }

  def coursierConfigModule: ModuleRef[CoursierConfigModule] =
    ModuleRef(CoursierConfigModule)

  /**
   * A [[CoursierModule.Resolver]] to resolve dependencies.
   *
   * Unlike [[defaultResolver]], this resolver can resolve Mill modules too
   * (obtained via [[JavaModule.coursierDependency]]).
   *
   * @return `CoursierModule.Resolver` instance
   */
  def millResolver: Task[CoursierModule.Resolver] = Task.Anon {
    new CoursierModule.Resolver(
      repositories = allRepositories(),
      bind = bindDependency(),
      mapDependencies = Some(mapDependencies()),
      customizer = resolutionCustomizer(),
      coursierCacheCustomizer = coursierCacheCustomizer(),
      resolutionParams = resolutionParams(),
      offline = Task.offline,
      checkGradleModules = checkGradleModules(),
      config = coursierConfigModule().coursierConfig()
    )
  }

  /**
   * A `CoursierModule.Resolver` to resolve dependencies.
   *
   * Can be used to resolve external dependencies, if you need to download an external
   * tool from Maven or Ivy repositories, by calling `CoursierModule.Resolver#classpath`.
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
      resolutionParams = resolutionParams(),
      offline = Task.offline,
      checkGradleModules = checkGradleModules(),
      config = coursierConfigModule().coursierConfig()
    )
  }

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
  private[mill] def internalRepositories: Task[Seq[Repository]] = Task.Anon(Nil)

  /**
   * A more flexible version of [[repositories]], that allows custom repositories
   * that are not definable by Coursier repository strings. This is a relatively
   * advanced feature, and for most situations overriding [[repositories]] should
   * be sufficient
   *
   * See [[allRepositories]] if you need to resolve Mill internal modules.
   */
  def repositoriesTask: Task[Seq[Repository]] = repositoriesTask0

  // Bincompat stub
  private[mill] def repositoriesTask0 = Task.Anon {
    val repos = coursierConfigModule().defaultRepositories()
    Jvm.reposFromStrings(repositories()).map(_ ++ repos)
  }

  /**
   * The repositories used to resolve dependencies with [[classpath()]]. This
   * takes Coursier repository strings as input which are defined here:
   *
   * - https://github.com/coursier/coursier/blob/main/docs/pages/reference-repositories.md
   *
   * For example:
   *
   * - https://repo1.maven.org/maven2
   *   Maven Central which is the most commonly used Maven repository
   *
   * - file:///Users/alex/test-repo
   *   A local repository cache on disk
   *
   * - file://${ivy.home-${user.home}/.ivy2}/local/[defaultPattern]
   *   The local Ivy2 repository where Mill, Scala CLI,
   *   or sbt publish to when asked to publish locally
   *
   * - file://${user.home}/.m2/repository
   *   the local Maven repository. Use of this is not recommended for reasons explained in the link above
   *
   * - https://maven.google.com
   *   Google-managed repository that distributes some Android artifacts in particular
   */
  def repositories: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * The repositories used to resolve dependencies
   *
   * Unlike [[repositoriesTask]], this includes the Mill internal repositories,
   * which allow to resolve Mill internal modules (usually brought in via
   * `JavaModule#coursierDependency`).
   *
   * Beware that this needs to evaluate `JavaModule#coursierProject` of all
   * module dependencies of the current module, which itself evaluates `JavaModule#mvnDeps`
   * and related tasks. You shouldn't depend on this task from implementations of `mvnDeps`,
   * which would introduce cycles between Mill tasks.
   */
  def allRepositories: Task[Seq[Repository]] = Task.Anon {
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
    ResolutionParams().addVariantAttributes(
      "org.gradle.category" -> VariantMatcher.Library,
      "org.gradle.jvm.environment" -> VariantMatcher.Equals("standard-jvm"),
      "org.gradle.dependency.bundling" -> VariantMatcher.Equals("external")
    )
  }

}
object CoursierModule {

  class Resolver(
      repositories: Seq[Repository],
      bind: Dep => BoundDep,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[coursier.core.Resolution => coursier.core.Resolution] = None,
      coursierCacheCustomizer: Option[
        coursier.cache.FileCache[coursier.util.Task] => coursier.cache.FileCache[coursier.util.Task]
      ] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      // TODO: this does nothing? :/
      // Introduced in https://github.com/com-lihaoyi/mill/commit/451df6846861a9c7d265bffec0c5fcf07133b320
      @unused offline: Boolean,
      checkGradleModules: Boolean,
      @unroll config: mill.util.CoursierConfig = mill.util.CoursierConfig.default()
  ) {

    /**
     * Class path of the passed dependencies
     *
     * @param deps root dependencies to resolve
     * @param sources whether to fetch source JARs or standard JARs
     */
    def classpath[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean = false,
        artifactTypes: Option[Set[coursier.Type]] = None,
        resolutionParamsMapOpt: Option[ResolutionParams => ResolutionParams] = None,
        mapDependencies: Option[Dependency => Dependency] = null
    )(implicit ctx: mill.api.TaskCtx): Seq[PathRef] =
      Lib.resolveDependencies(
        repositories = repositories,
        deps = deps.iterator.map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind)),
        sources = sources,
        artifactTypes = artifactTypes,
        mapDependencies = Option(mapDependencies).getOrElse(this.mapDependencies),
        customizer = customizer,
        coursierCacheCustomizer = coursierCacheCustomizer,
        ctx = Some(ctx),
        resolutionParams = resolutionParamsMapOpt.fold(resolutionParams)(_(resolutionParams)),
        checkGradleModules = checkGradleModules,
        config = config
      ).get

    /**
     * Raw coursier resolution of the passed dependencies
     *
     * @param deps root dependencies
     */
    def resolution[T: CoursierModule.Resolvable](
        deps: IterableOnce[T]
    )(implicit ctx: mill.api.TaskCtx): coursier.core.Resolution = {
      val deps0 = deps
        .iterator
        .map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind))
        .toSeq
      Lib.resolveDependenciesMetadataSafe(
        repositories = repositories,
        deps = deps0,
        mapDependencies = mapDependencies,
        customizer = customizer,
        coursierCacheCustomizer = coursierCacheCustomizer,
        ctx = Some(ctx),
        resolutionParams = resolutionParams,
        boms = Nil,
        checkGradleModules = checkGradleModules,
        config = config
      ).get
    }

    /**
     * Raw artifact results for the passed dependencies
     */
    def artifacts[T: CoursierModule.Resolvable](
        deps: IterableOnce[T],
        sources: Boolean = false
    )(implicit ctx: mill.api.TaskCtx): coursier.Artifacts.Result = {
      val deps0 = deps
        .iterator
        .map(implicitly[CoursierModule.Resolvable[T]].bind(_, bind))
        .toSeq
      Jvm.getArtifacts(
        repositories,
        deps0.map(_.dep),
        sources = sources,
        ctx = Some(ctx),
        checkGradleModules = checkGradleModules,
        config = config
      ).get
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
