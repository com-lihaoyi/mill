package mill.scalalib

import mill.*
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import HelloWorldTests.*
import mill.api.Discover
object ScalaMacrosTests extends TestSuite {

  object HelloWorldMacros213 extends TestRootModule {
    object core extends ScalaModule {
      override def scalaVersion = scala213Version
      override def mvnDeps = Seq(mvn"com.github.julien-truffaut::monocle-macro::2.1.0")
      override def scalacOptions = super.scalacOptions() ++ Seq("-Ymacro-annotations")
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("macros") {
      test("scala-2.13") {
        // make sure macros are applied when compiling/running
        val mod = HelloWorldMacros213
        test("runMain") - UnitTester(
          mod,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-macros"
        ).scoped { eval =>
          val Right(result) = eval.apply(mod.core.runMain("Main")): @unchecked
          assert(result.evalCount > 0)
        }
        // make sure macros are applied when compiling during scaladoc generation
        test("docJar") - UnitTester(
          mod,
          sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-macros"
        ).scoped { eval =>
          val Right(result) = eval.apply(mod.core.docJar): @unchecked
          assert(result.evalCount > 0)
        }
      }
    }
  }
}
