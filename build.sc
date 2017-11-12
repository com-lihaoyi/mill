#!/usr/bin/env amm
import $cp.scalaplugin.target.`scala-2.12`.`mill-scalaplugin-assembly-0.1-SNAPSHOT.jar`
import ammonite.ops.pwd
import mill.discover.Discovered
import mill.eval.{Evaluator, PathRef}
import mill.scalaplugin.Subproject.Dep
import mill.util.OSet
import mill.{T, _}
import mill.scalaplugin.{TestRunner, _}
@main def main(args: String*) = mill.Main(args, Build)
object Build{
  trait MillSubproject extends Subproject{
    def scalaVersion = T{ "2.12.4" }
  }

  object Core extends MillSubproject {

    override def compileIvyDeps = T{
      Seq(
        Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
      )
    }

    override def ivyDeps = T{
      Seq(
        Dep("com.lihaoyi", "sourcecode", "0.1.4"),
        Dep("com.lihaoyi", "pprint", "0.5.3"),
        Dep.Point("com.lihaoyi", "ammonite", "1.0.3"),
        Dep("com.typesafe.play", "play-json", "2.6.6"),
        Dep("org.scala-sbt", "zinc", "1.0.3"),
        Dep.Java("org.scala-sbt", "test-interface", "1.0")
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
      Seq[Dep](
        Dep("com.lihaoyi", "utest", "0.6.0")
      )
    }
    def test() = T.command{
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


