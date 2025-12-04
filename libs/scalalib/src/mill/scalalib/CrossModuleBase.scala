package mill.scalalib

import mill.{T, Task}
import mill.api.Cross
import mill.api.Cross.Resolver
import mill.javalib.api.JvmWorkerUtil

/**
 * Shared logic between the different kinds of cross-scala-version modules:
 * [[CrossSbtModule]], [[CrossScalaModule]]
 */
trait CrossModuleBase extends ScalaModule with Cross.Module[String] {
  def crossScalaVersion: String = crossValue

  def scalaVersion = Task { crossScalaVersion }

  protected def scalaVersionDirectoryNames: Seq[String] =
    JvmWorkerUtil.matchingVersions(crossScalaVersion)

  override def crossWrapperSegments: List[String] = moduleSegments.parts

  override def artifactNameParts: T[Seq[String]] =
    super.artifactNameParts().patch(crossWrapperSegments.size - 1, Nil, 1)

  implicit def crossSbtModuleResolver: Resolver[CrossModuleBase] =
    new Resolver[CrossModuleBase] {
      def resolve[V <: CrossModuleBase](c: Cross[V]): V = {
        crossScalaVersion
          .split('.')
          .inits
          .takeWhile(_.length > (if (JvmWorkerUtil.isScala3(crossScalaVersion)) 0 else 1))
          .flatMap(prefix =>
            c.crossModules
              .find(_.crossScalaVersion.split('.').startsWith(prefix))
          )
          .collectFirst { case x => x }
          .getOrElse(
            throw Exception(
              s"Unable to find compatible cross version between $crossScalaVersion and " +
                c.crossModules.map(_.crossScalaVersion).mkString(",")
            )
          )
      }
    }
}
