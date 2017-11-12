import $cp.scalaplugin.target.`scala-2.12`.classes
import ammonite.ops.pwd
import coursier.{Dependency => Dep, Module => Mod}
import mill.discover.Discovered
import mill.eval.{Evaluator, PathRef}
import mill.scalaplugin.Subproject.ScalaDep
import mill.util.OSet
import mill.{T, _}
import mill.scalaplugin.{TestRunner, _}

object Build{
  trait MillSubproject extends Subproject{
    def scalaVersion = T{ "2.12.4" }
  }

  object Core extends MillSubproject {

    override def compileIvyDeps = T{
      Seq[ScalaDep](
        Dep(Mod("org.scala-lang", "scala-reflect"), scalaVersion(), configuration = "provided")
      )
    }

    override def ivyDeps = T{
      Seq[ScalaDep](
        ScalaDep(Dep(Mod("com.lihaoyi", "sourcecode"), "0.1.4")),
        ScalaDep(Dep(Mod("com.lihaoyi", "pprint"), "0.5.3")),
        ScalaDep.Point(Dep(Mod("com.lihaoyi", "ammonite"), "1.0.3")),
        ScalaDep(Dep(Mod("com.typesafe.play", "play-json"), "2.6.6")),
        ScalaDep(Dep(Mod("org.scala-sbt", "zinc"), "1.0.3")),
        Dep(Mod("org.scala-sbt", "test-interface"), "1.0")
      )
    }

    def basePath = T{ pwd / 'core }
    override def sources = T{ pwd/'core/'src/'main/'scala }
  }
  object CoreTests extends MillSubproject {
    override def projectDeps = Seq(Core)
    def basePath = T{ pwd / 'scalaplugin }
    override def sources = T{ pwd/'core/'src/'test/'scala }
    override def ivyDeps = T{
      Seq[ScalaDep](
        ScalaDep(Dep(Mod("com.lihaoyi", "utest"), "0.6.0"))
      )
    }
    def test() = T.command{
      pprint.log(runDepClasspath().map(_.path.toString), height=999)
      TestRunner.apply(
        "mill.UTestFramework",
        runDepClasspath().map(_.path) :+ compiled().path,
        Seq(compiled().path)
      )
    }
  }

  object ScalaPlugin extends MillSubproject {
    override def projectDeps = Seq(Core)
    def basePath = T{ pwd / 'scalaplugin }
    override def sources = T{ pwd/'scalaplugin/'src/'main/'scala }
  }
}
@main def main(): Any = Build -> mill.discover.Discovered[Build.type]
