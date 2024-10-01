package mill.scalalib

import mill.testkit.{TestBaseModule, UnitTester}
import utest._
import HelloWorldTests._
object ScalaFlagsTests extends TestSuite {

  object HelloWorldFlags extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = scala212Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Ypartial-unification"
      )
    }
  }

  def tests: Tests = Tests {

    test("flags") {
      // make sure flags are passed when compiling/running
      test("runMain") - UnitTester(
        HelloWorldFlags,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-flags"
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldFlags.core.runMain("Main"))
        assert(result.evalCount > 0)
      }
      // make sure flags are passed during ScalaDoc generation
      test("docJar") - UnitTester(
        HelloWorldFlags,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-flags"
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldFlags.core.docJar)
        assert(result.evalCount > 0)
      }
    }
  }
}
