package mill.scalalib

import mill.T
import mill.define.Cross
import mill.define.Cross.Resolver
import mill.scalalib.api.ZincWorkerUtil

trait CrossModuleBase extends ScalaModule {
  def crossScalaVersion: String
  def scalaVersion = T { crossScalaVersion }

  protected def scalaVersionDirectoryNames: Seq[String] =
    ZincWorkerUtil.matchingVersions(crossScalaVersion)

  protected def wrapperSegments =  millModuleSegments.parts
  override def artifactNameParts = super.artifactNameParts().patch(wrapperSegments.size - 1, Nil, 1)
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
