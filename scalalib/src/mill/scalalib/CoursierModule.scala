package mill.scalalib

import _root_.{coursier => c}
import c.Dependency
import c.Repository
import c.cache.FileCache
import c.core.Resolution
import c.credentials.Credentials
import c.Resolve
import c.Repository
import c.cache.CacheDefaults
import c.ivy.IvyRepository
import c.maven.MavenRepository

import mill.Agg
import mill.api.Ctx
import mill.T
import mill.Worker
import mill.api.PathRef
import mill.define.Task

import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global


/**
 * This module provides the capability to resolve (transitive) dependencies from (remote) repositories.
 *
 * It's mainly used in [[JavaModule]], but can also be used stand-alone,
 * in which case you must provide repositories by overriding [[CoursierModule.repositoriesTask]].
 */
trait CoursierModule extends mill.Module {

  /**
   * Sets up the coursier proxy, using coursier's configuration sources.
   *
   * @see [[https://scala-cli.virtuslab.org/docs/guides/power/proxy/] Scala-cli proxy]
   * @see [[https://get-coursier.io/docs/other-proxy] coursier proxy]
   * @see [[https://github.com/coursier/coursier/blob/f6f82f2e7b55deb461dd041f8f11df428bcf3789/modules/coursier/jvm/src/main/scala/coursier/PlatformResolve.scala#L101] PlatformResolve.scala#L101]
   */
  def setupProxy(implicit ctx: Ctx): Worker[Unit] = T.worker {
    T.input {
      T.task {
        Resolve.proxySetup()
      }.evaluate(ctx)
    }.t
  }

  /**
   * Gets the coursier credentials from coursier's configuration sources.
   *
   * @see [[https://github.com/coursier/coursier/blob/f6f82f2e7b55deb461dd041f8f11df428bcf3789/modules/cache/jvm/src/main/scala/coursier/cache/CacheDefaults.scala#L97] CacheDefaults.credentials]
   * @see [[https://get-coursier.io/docs/other-credentials] Coursier Credentials]
   * @see [[https://scala-cli.virtuslab.org/docs/guides/power/repositories/#repository-authentication] Scala-cli Repository Authentication]
   * @return the list of coursier credentials
   */
  def coursierCredentials(implicit ctx: Ctx): Worker[Seq[Credentials]] = T.worker {
    T.input {
      T.task {
        CacheDefaults.credentials.flatMap { _.get() }
      }.evaluate(ctx)
    }.t
  }

  /**
   * Sets up repositories with mirrors, proxies, and credentials from coursier's config sources, without overwriting existing authentication.
   *
   * @return a worker containing the result of applying the coursier credentials, proxy setup, and mirrors to the repositories in coursiers configs
   */
  def repositoriesWithCredentials(implicit ctx: Ctx): Worker[Seq[Repository]] = T.worker {
    T.input {
      T.task {
        val _ = setupProxy(ctx)
        val credentials = coursierCredentials(ctx)
        Await.result(
          Resolve().finalRepositories.map {
            _.map {
              case x: IvyRepository if x.authentication.isEmpty =>
                x.withAuthentication(
                  resolvedCredentials.find { c =>
                    c.matches(x.pattern.string, c.usernameOpt.getOrElse(""))
                  }.map(_.authentication)
                )
              case x: MavenRepository if x.authentication.isEmpty =>
                x.withAuthentication(
                  resolvedCredentials.find { c =>
                    c.matches(x.root, c.usernameOpt.getOrElse(""))
                  }.map(_.authentication)
                )
              case r => r
            }
          }.future(),
          Duration.Inf
        )
      }
    }.t
  }

  /**
   * Bind a dependency ([[Dep]]) to the actual module contetxt (e.g. the scala version and the platform suffix)
   * @return The [[BoundDep]]
   */
  def bindDependency: Task[Dep => BoundDep] = T.task { dep: Dep =>
    BoundDep((resolveCoursierDependency(): @nowarn).apply(dep), dep.force)
  }

  @deprecated("To be replaced by bindDependency", "Mill after 0.11.0-M0")
  def resolveCoursierDependency: Task[Dep => c.Dependency] = T.task {
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
   * The repositories used to resolve dependencies with [[resolveDeps()]].
   */
  def repositoriesTask: Task[Seq[Repository]] = T.task {
    // what's the idiomatic way to do this @lefou?
    repositoriesWithCredentials(implicitly)()
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
  def coursierCacheCustomizer: Task[Option[FileCache[c.util.Task] => FileCache[c.util.Task]]] =
    T.task { None }

}
