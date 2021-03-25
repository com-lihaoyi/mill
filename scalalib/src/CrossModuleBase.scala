package mill.scalalib

import mill.T
import mill.define.Cross
import mill.define.Cross.Resolver
import mill.api.PathRef

object CrossModuleBase {
  def scalaVersionPaths(scalaVersion: String, crossScalaVersions: Seq[String], f: String => os.Path) = {
    api.Util.versionSpecificSources(scalaVersion, crossScalaVersions).map(s => PathRef(f(s)))
  }
}
trait CrossModuleBase extends ScalaModule {
  def crossScalaVersion: String
  def scalaVersion = T { crossScalaVersion }

  override def millSourcePath = super.millSourcePath / ammonite.ops.up
  override def artifactName: T[String] = millModuleSegments.parts.init.mkString("-")
  implicit def crossSbtModuleResolver: Resolver[CrossModuleBase] =
    new Resolver[CrossModuleBase] {
      def resolve[V <: CrossModuleBase](c: Cross[V]): V = {
        crossScalaVersion
          .split('.')
          .inits
          .takeWhile(_.length > 1)
          .flatMap(
            prefix =>
              c.items
                .map(_._2)
                .find(_.crossScalaVersion.split('.').startsWith(prefix)))
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
