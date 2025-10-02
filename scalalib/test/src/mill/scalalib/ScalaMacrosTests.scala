package mill.scalalib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._
import scala.util.Properties
import HelloWorldTests._
object ScalaMacrosTests extends TestSuite {

  object HelloWorldMacros212 extends TestBaseModule {
    object core extends ScalaModule {
      override def scalaVersion = scala212Version
      override def ivyDeps = Agg(
        mvn"com.github.julien-truffaut::monocle-macro::1.6.0"
      )
      override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ Agg(
        mvn"org.scalamacros:::paradise:2.1.0"
      )
    }
  }

  object HelloWorldMacros213 extends TestBaseModule {
    object core extends ScalaModule {
      override def scalaVersion = scala213Version
      override def ivyDeps = Agg(mvn"com.github.julien-truffaut::monocle-macro::2.1.0")
      override def scalacOptions = super.scalacOptions() ++ Seq("-Ymacro-annotations")
    }
  }

  def tests: Tests = Tests {

    test("macros") {
      test("scala-2.12") {
        // Scala 2.12 does not always work with Java 17+
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros212
        test("runMain") - UnitTester(
          mod,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-macros"
        ).scoped { eval =>
          if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
          else {
            val Right(result) = eval.apply(mod.core.runMain("Main"))
            assert(result.evalCount > 0)
          }
        }
        // make sure macros are applied when compiling during scaladoc generation
        test("docJar") - UnitTester(
          mod,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-macros"
        ).scoped { eval =>
          if (Properties.isJavaAtLeast(17)) "skipped on Java 17+"
          else {
            val Right(result) = eval.apply(mod.core.docJar)
            assert(result.evalCount > 0)
          }
        }
      }
      test("scala-2.13") {
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros213
        test("runMain") - UnitTester(
          mod,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-macros"
        ).scoped { eval =>
          val Right(result) = eval.apply(mod.core.runMain("Main"))
          assert(result.evalCount > 0)
        }
        // make sure macros are applied when compiling during scaladoc generation
        test("docJar") - UnitTester(
          mod,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-macros"
        ).scoped { eval =>
          val Right(result) = eval.apply(mod.core.docJar)
          assert(result.evalCount > 0)
        }
      }
    }
  }
}
