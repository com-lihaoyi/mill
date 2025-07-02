package mill.scalalib

import mill.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.api.Discover
object ScalaTypeLevelTests extends TestSuite {

  object HelloWorldTypeLevel extends TestRootModule {
    object foo extends ScalaModule {
      override def scalaVersion = "2.11.8"
      override def scalaOrganization = "org.typelevel"
      override def ammoniteVersion = "1.6.7"

      override def mvnDeps = Seq(
        mvn"com.github.julien-truffaut::monocle-macro::1.4.0"
      )
      override def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ Seq(
        mvn"org.scalamacros:::paradise:2.1.0"
      )
      override def scalaDocPluginMvnDeps = super.scalaDocPluginMvnDeps() ++ Seq(
        mvn"com.typesafe.genjavadoc:::genjavadoc-plugin:0.11"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("scalacPluginClasspath") {
      test("withMacroParadise") - UnitTester(HelloWorldTypeLevel, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldTypeLevel.foo.scalacPluginClasspath): @unchecked
        assert(
          result.value.nonEmpty,
          result.value.iterator.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          result.evalCount > 0
        )
      }
    }

    test("scalaDocPluginClasspath") {
      test("extend") - UnitTester(HelloWorldTypeLevel, sourceRoot = resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldTypeLevel.foo.scalaDocPluginClasspath): @unchecked
        assert(
          result.value.iterator.nonEmpty,
          result.value.iterator.exists { pathRef => pathRef.path.segments.contains("scalamacros") },
          result.value.iterator.exists { pathRef => pathRef.path.segments.contains("genjavadoc") },
          result.evalCount > 0
        )
      }
    }

    test("typeLevel") - UnitTester(HelloWorldTypeLevel, null).scoped { eval =>
      val classPathsToCheck = Seq(
        HelloWorldTypeLevel.foo.runClasspath,
        HelloWorldTypeLevel.foo.ammoniteReplClasspath,
        HelloWorldTypeLevel.foo.compileClasspath
      )
      for (cp <- classPathsToCheck) {
        val Right(result) = eval.apply(cp): @unchecked
        assert(
          // Make sure every relevant piece org.scala-lang has been substituted for org.typelevel
          !result.value.map(_.toString).exists(x =>
            x.contains("scala-lang") &&
              (x.contains("scala-library") || x.contains("scala-compiler") || x.contains(
                "scala-reflect"
              ))
          ),
          result.value.map(_.toString).exists(x =>
            x.contains("typelevel") && x.contains("scala-library")
          )
        )
      }
    }

  }
}
