package mill.scalalib

import mill.T
import mill.define.Cross
import mill.define.Cross.Resolver
import mill.api.PathRef

object CrossModuleBase {
  @deprecated("Use mill.scalalib.api.Util.matchingVersions instead", since = "0.10.0")
  def scalaVersionPaths(scalaVersion: String, f: String => os.Path): Iterator[PathRef] = {
    api.Util.matchingVersions(scalaVersion).iterator.map(version => PathRef(f(version)))
  }
}

trait CrossModuleBase extends ScalaModule {
  def crossScalaVersion: String
  def scalaVersion = T { crossScalaVersion }

  protected def scalaVersionDirectoryNames: Seq[String] =
    api.Util.matchingVersions(crossScalaVersion)

  override def millSourcePath = super.millSourcePath / os.up
  override def artifactName: T[String] = millModuleSegments.parts.init.mkString("-")
  implicit def crossSbtModuleResolver: Resolver[CrossModuleBase] =
    new Resolver[CrossModuleBase] {
      def resolve[V <: CrossModuleBase](c: Cross[V]): V = {
        crossScalaVersion
          .split('.')
          .inits
          .takeWhile(_.length > 1)
          .flatMap(prefix =>
            c.items
              .map(_._2)
              .find(_.crossScalaVersion.split('.').startsWith(prefix))
          )
          .collectFirst { case x => x }
          .getOrElse(
            throw new Exception(
              s"Unable to find compatible cross version between $crossScalaVersion and " +
                c.items.map(_._2.crossScalaVersion).mkString(",")
            )
          )
      }
    }
}
