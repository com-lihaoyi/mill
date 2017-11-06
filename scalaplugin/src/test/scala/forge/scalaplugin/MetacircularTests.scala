package forge
package scalaplugin

import ammonite.ops.pwd
import coursier.{Dependency => Dep, Module => Mod}
import forge.discover.Discovered
import forge.eval.{Evaluator, PathRef}
import forge.scalaplugin.Subproject.ScalaDep
import forge.util.OSet
import utest._

object MetacircularTests extends TestSuite{
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

  val tests = Tests{
    'scalac {
      val workspacePath = pwd / 'target / 'workspace / 'meta
      val mapping = Discovered.mapping(MetacircularTests)
      val evaluator = new Evaluator(workspacePath, mapping)
//      val evaluated1 = evaluator.evaluate(OSet(Self.scalaVersion)).evaluated.collect(mapping)
//      val evaluated2 = evaluator.evaluate(OSet(Self.scalaBinaryVersion)).evaluated.collect(mapping)
//      val evaluated3 = evaluator.evaluate(OSet(Self.compileDeps)).evaluated.collect(mapping)
//      val evaluated4 = evaluator.evaluate(OSet(Self.deps)).evaluated.collect(mapping)
//      val evaluated5 = evaluator.evaluate(OSet(Core.compiled)).evaluated.collect(mapping)
//      val evaluated6 = evaluator.evaluate(OSet(ScalaPlugin.compiled)).evaluated.collect(mapping)
//      val evaluated7 = evaluator.evaluate(OSet(ScalaPlugin.jar)).evaluated.collect(mapping)
//      evaluated3
    }
  }
}

