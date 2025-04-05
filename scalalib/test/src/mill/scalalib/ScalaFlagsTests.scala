package mill.scalalib

import mill.testkit.{TestBaseModule, UnitTester}
import utest.*
import HelloWorldTests.*
import mill.define.Discover
import mill.util.TokenReaders._

object ScalaFlagsTests extends TestSuite {

  object HelloWorldFlags extends TestBaseModule {
    object core extends ScalaModule {
      def scalaVersion = scala212Version

      override def scalacOptions = super.scalacOptions() ++ Seq(
        "-Ypartial-unification"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("flags") {
      // make sure flags are passed when compiling/running
      test("runMain") - UnitTester(
        HelloWorldFlags,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-flags"
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldFlags.core.runMain("Main")): @unchecked
        assert(result.evalCount > 0)
      }
      // make sure flags are passed during ScalaDoc generation
      test("docJar") - UnitTester(
        HelloWorldFlags,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-flags"
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldFlags.core.docJar): @unchecked
        assert(result.evalCount > 0)
      }
    }
  }
}
