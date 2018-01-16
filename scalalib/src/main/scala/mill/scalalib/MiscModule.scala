package mill
package scalalib

import mill.define.Cross.Resolver
import mill.define.{Cross, Task}
import mill.eval.{PathRef, Result}
import mill.util.Loose.Agg


trait SbtModule extends ScalaModule { outer =>
  override def sources = T.input{ Agg(PathRef(basePath / 'src / 'main / 'scala)) }
  override def resources = T.input{ Agg(PathRef(basePath / 'src / 'main / 'resources)) }
  trait Tests extends super.Tests {
    override def basePath = outer.basePath
    override def sources = T.input{ Agg(PathRef(basePath / 'src / 'test / 'scala)) }
    override def resources = T.input{ Agg(PathRef(basePath / 'src / 'test / 'resources)) }
  }
}

trait CrossSbtModule extends SbtModule { outer =>
  override def basePath = super.basePath / ammonite.ops.up
  implicit def crossSbtModuleResolver: Resolver[CrossSbtModule] = new Resolver[CrossSbtModule]{
    def resolve[V <: CrossSbtModule](c: Cross[V]): V = {
      crossScalaVersion.split('.')
        .inits
        .takeWhile(_.length > 1)
        .flatMap( prefix =>
          c.items.map(_._2).find(_.crossScalaVersion.split('.').startsWith(prefix))
        )
        .collectFirst{case x => x}
        .getOrElse(
          throw new Exception(
            s"Unable to find compatible cross version between $crossScalaVersion and "+
            c.items.map(_._2.crossScalaVersion).mkString(",")
          )
        )
    }
  }

  def crossScalaVersion: String
  def scalaVersion = crossScalaVersion
  override def sources = T.input{
    super.sources() ++
    crossScalaVersion.split('.').inits.filter(_.nonEmpty).map(_.mkString(".")).map{
      s => PathRef{ basePath / 'src / 'main / s"scala-$s" }
    }

  }
  override def resources = T.input{ Agg(PathRef(basePath / 'src / 'main / 'resources)) }
  trait Tests extends super.Tests {
    override def basePath = outer.basePath
    override def sources = T.input{
      super.sources() ++
      crossScalaVersion.split('.').inits.filter(_.nonEmpty).map(_.mkString(".")).map{
        s => PathRef{ basePath / 'src / 'test / s"scala-$s" }
      }
    }
    override def resources = T.input{ Agg(PathRef(basePath / 'src / 'test / 'resources)) }
  }
}


