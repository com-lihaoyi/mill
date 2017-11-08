import $cp.scalaplugin.target.`scala-2.12`.classes
import ammonite.ops.pwd
import coursier.{Dependency => Dep, Module => Mod}
import forge.discover.Discovered
import forge.eval.{Evaluator, PathRef}
import forge.scalaplugin.Subproject.ScalaDep
import forge.util.OSet
import forge._
import forge.scalaplugin._

object Build{


  object Core extends Subproject {
    def scalaVersion = T{ "2.12.4" }
    override def compileIvyDeps = T{
      super.compileIvyDeps() ++ Seq[ScalaDep](
        Dep(Mod("org.scala-lang", "scala-reflect"), scalaVersion(), configuration = "provided")
      )
    }

    override def ivyDeps = T{
      super.ivyDeps() ++ Seq[ScalaDep](
        ScalaDep(Dep(Mod("com.lihaoyi", "sourcecode"), "0.1.4")),
        ScalaDep(Dep(Mod("com.lihaoyi", "pprint"), "0.5.3")),
        ScalaDep.Point(Dep(Mod("com.lihaoyi", "ammonite"), "1.0.3")),
        ScalaDep(Dep(Mod("com.typesafe.play", "play-json"), "2.6.6")),
        ScalaDep(Dep(Mod("org.scala-sbt", "zinc"), "1.0.3"))
      )
    }


    def basePath = T{ pwd / 'core }
    override def sources = T{ PathRef(pwd/'core/'src/'main/'scala) }
    override def resources = T{ sources }
  }
  object ScalaPlugin extends Subproject {
    def scalaVersion = T{ "2.12.4" }

    override def depClasspath = T{ Seq(Core.compiled()) }
    override def ivyDeps = T{ Core.ivyDeps }
    def basePath = T{ pwd / 'scalaplugin }
    override def sources = T{ PathRef(pwd/'scalaplugin/'src/'main/'scala) }
    override def resources = T{ sources }
  }
}
@main def main(): Any = Build -> forge.discover.Discovered[Build.type]
