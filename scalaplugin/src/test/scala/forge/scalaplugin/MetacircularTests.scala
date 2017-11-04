package forge
package scalaplugin

import ammonite.ops.pwd
import coursier.{Dependency => Dep, Module => Mod}
import forge.util.{OSet, PathRef}
import utest._

object MetacircularTests extends TestSuite{
  object Core extends Subproject {
    val scalaVersion = T{ "2.12.4" }
    override val compileIvyDeps = T{
      Seq(
        Dep(Mod("org.scala-lang", "scala-reflect"), scalaVersion(), configuration = "provided")
      )
    }

    override val ivyDeps = T{
      Seq(
        Dep(Mod("com.lihaoyi", "sourcecode_" + scalaBinaryVersion()), "0.1.4"),
        Dep(Mod("com.lihaoyi", "pprint_" + scalaBinaryVersion()), "0.5.3"),
        Dep(Mod("com.lihaoyi", "ammonite_" + scalaVersion()), "1.0.3"),
        Dep(Mod("com.typesafe.play", "play-json_" + scalaBinaryVersion()), "2.6.6"),
        Dep(Mod("org.scala-sbt", "zinc_" + scalaBinaryVersion()), "1.0.3")
      )
    }


    val basePath = T{ pwd / 'core }
    override val sources = T{ PathRef(pwd/'core/'src/'main/'scala) }
    override val resources = T{ sources }
  }
  object ScalaPlugin extends Subproject {
    val scalaVersion = T{ "2.12.4" }

    override val depClasspath = T{ Seq(Core.compiled()) }
    val basePath = T{ pwd / 'scalaplugin }
    override val sources = T{ PathRef(pwd/'scalaplugin/'src/'main/'scala) }
    override val resources = T{ sources }
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
      val evaluated5 = evaluator.evaluate(OSet(Core.compiled)).evaluated.collect(mapping)
      val evaluated6 = evaluator.evaluate(OSet(ScalaPlugin.compiled)).evaluated.collect(mapping)
//      evaluated3
    }
  }
}

