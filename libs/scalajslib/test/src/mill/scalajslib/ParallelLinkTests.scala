package mill.scalajslib

import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest._

object ParallelLinkTests extends TestSuite {
  object ParallelLinkTests extends TestRootModule {

    object parallelLinkedModule extends ScalaJSModule {
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
    }

    object sequentiallyLinkedModule extends ScalaJSModule {
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
      override def scalaJSParallel = false
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  val tests: Tests = Tests {

    test("parallel linking is true by default") - UnitTester(
      ParallelLinkTests,
      millSourcePath
    ).scoped { evaluator =>
      val result = evaluator(ParallelLinkTests.parallelLinkedModule.scalaJSParallel)
      assert(result.isRight)
      assert(result.map(_.value).contains(true))
    }

    test("parallel linking can be disabled") - UnitTester(
      ParallelLinkTests,
      millSourcePath
    ).scoped { evaluator =>
      val result = evaluator(ParallelLinkTests.sequentiallyLinkedModule.scalaJSParallel)
      assert(result.isRight)
      assert(result.map(_.value).contains(false))
    }

    test("should build correctly with parallelism disabled") {
      UnitTester(ParallelLinkTests, millSourcePath).scoped { evaluator =>
        val Right(result) =
          evaluator(ParallelLinkTests.sequentiallyLinkedModule.fastLinkJS): @unchecked
        val publicModules = result.value.publicModules.toSeq
        assert(publicModules.length == 1)
        val main = publicModules.head
        assert(main.jsFileName == "main.js")
        assert(os.exists(result.value.dest.path / "main.js"))
      }
    }

  }
}
