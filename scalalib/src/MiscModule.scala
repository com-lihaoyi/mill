package mill
package scalalib

import mill.define.Cross.Resolver
import mill.define.{Cross, Task}
import mill.eval.{PathRef, Result}
import mill.util.Loose.Agg
object CrossModuleBase{
  def scalaVersionPaths(scalaVersion: String, f: String => os.Path) = {
    for(segments <- scalaVersion.split('.').inits.filter(_.nonEmpty))
    yield PathRef(f(segments.mkString(".")))
  }
}
trait CrossModuleBase extends ScalaModule {
  def crossScalaVersion: String
  def scalaVersion = T{ crossScalaVersion }

  override def millSourcePath = super.millSourcePath / ammonite.ops.up
  implicit def crossSbtModuleResolver: Resolver[CrossModuleBase] = new Resolver[CrossModuleBase]{
    def resolve[V <: CrossModuleBase](c: Cross[V]): V = {
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
}

trait CrossScalaModule extends ScalaModule with CrossModuleBase{ outer =>
    override def sources = T.sources{
    super.sources() ++
    CrossModuleBase.scalaVersionPaths(crossScalaVersion, s => millSourcePath / s"src-$s" )
  }

  trait Tests extends super.Tests {
    override def sources = T.sources{
      super.sources() ++
      CrossModuleBase.scalaVersionPaths(crossScalaVersion, s => millSourcePath / s"src-$s" )
    }
  }
}

trait MavenTests extends TestModule{
  override def sources = T.sources(
    millSourcePath / 'src / 'test / 'scala,
    millSourcePath / 'src / 'test / 'java
  )
  override def resources = T.sources{ millSourcePath / 'src / 'test / 'resources }
}
trait MavenModule extends JavaModule{outer =>

  override def sources = T.sources(
    millSourcePath / 'src / 'main / 'scala,
    millSourcePath / 'src / 'main / 'java
  )
  override def resources = T.sources{ millSourcePath / 'src / 'main / 'resources }
  trait Tests extends super.Tests with MavenTests {
    override def millSourcePath = outer.millSourcePath
    override def intellijModulePath = outer.millSourcePath / 'src / 'test
  }
}

trait SbtModule extends MavenModule with ScalaModule{ outer =>
  trait Tests extends super.Tests with MavenTests {
    override def millSourcePath = outer.millSourcePath
    override def intellijModulePath = outer.millSourcePath / 'src / 'test
  }
}

trait CrossSbtModule extends SbtModule with CrossModuleBase{ outer =>

  override def sources = T.sources{
    super.sources() ++
    CrossModuleBase.scalaVersionPaths(
      crossScalaVersion,
      s => millSourcePath / 'src / 'main / s"scala-$s"
    )

  }
  trait Tests extends super.Tests {
    override def millSourcePath = outer.millSourcePath
    override def sources = T.sources{
      super.sources() ++
      CrossModuleBase.scalaVersionPaths(
        crossScalaVersion,
        s => millSourcePath / 'src / 'test / s"scala-$s"
      )
    }
  }
}


