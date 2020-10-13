package mill.scalalib

import mill.T
import mill.define.Cross
import mill.define.Cross.Resolver
import mill.eval.PathRef

object CrossModuleBase {
  def scalaVersionPaths(scalaVersion: String, f: String => os.Path) = {
    for (segments <- scalaVersion.split('.').inits.filter(_.nonEmpty))
      yield PathRef(f(segments.mkString(".")))
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
