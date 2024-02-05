package mill
package scalalib
package coursier

import mill._
import mill.scalalib._
import _root_.coursier.credentials.Credentials
import _root_.coursier.Resolve
import _root_.coursier.cache.CacheDefaults
import _root_.coursier.ivy.IvyRepository
import _root_.coursier.maven.MavenRepository

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Handles coursier configuration integration.
 */
trait CoursierWorkerModule {

  /**
   * Sets up the coursier proxy, using coursier's configuration sources.
   *
   * @see [[https://scala-cli.virtuslab.org/docs/guides/power/proxy/] Scala-cli proxy]
   * @see [[https://get-coursier.io/docs/other-proxy] coursier proxy]
   * @see [[https://github.com/coursier/coursier/blob/f6f82f2e7b55deb461dd041f8f11df428bcf3789/modules/coursier/jvm/src/main/scala/coursier/PlatformResolve.scala#L101] PlatformResolve.scala#L101]
   */
  def setupProxy: Worker[Boolean] = T.worker {
    T.input {
      T.task {
        Resolve.proxySetup()
      }.evaluate()
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
  def coursierCredentials: Worker[Seq[Credentials]] = T.worker {
    T.input {
      T.task {
        CacheDefaults.credentials.flatMap { _.get() }
      }.evaluate()
    }.t
  }

  /**
   * Sets up repositories with mirrors, proxies, and credentials from
   * coursier's config sources.
   *
   * @return a worker containing the result of applying the coursier credentials, proxy setup, and mirrors to the repositories in coursiers configs
   */
  def repositoriesWithCredentials: Worker[Seq[Repository]] = T.worker {
    T.input {
      T.task {
        val _ = setupProxy()
        val credentials = coursierCredentials()
        Await.result(
          Resolve().finalRepositories.map {
            _.map {
              case x: IvyRepository =>
                x.withAuthentication(
                  resolvedCredentials.find { c =>
                    c.matches(x.pattern.string, c.usernameOpt.getOrElse(""))
                  }.map(_.authentication)
                )
              case x: MavenRepository =>
                x.withAuthentication(
                  resolvedCredentials.find { c =>
                    c.matches(x.root, c.usernameOpt.getOrElse(""))
                  }.map(_.authentication)
                )
            }
          }.future(),
          Duration.Inf
        )
      }
    }.t
  }

}

object CoursierWorkerModule {

  def apply(): CoursierWorkerModule = new CoursierWorkerModule {}

}
