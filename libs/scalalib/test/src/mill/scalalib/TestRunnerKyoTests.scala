package mill.scalalib

import mill.{T, Task}
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

object TestRunnerKyoTests extends TestSuite {

  object kyoTestModule extends TestRootModule with ScalaModule with TestModule.KyoTest {
    override def scalaVersion: T[String] = sys.props.getOrElse("MILL_SCALA_3_NEXT_VERSION", ???)
    override def kyoTestVersion: T[String] = 
      sys.props.getOrElse("TEST_KYO_TEST_RUNNER_VERSION", ???)
    // The published kyo-test-runner POM does not pull in kyo-core, which the
    // kyo.test.Test API needs on the compile classpath, so add it explicitly.
    override def mvnDeps = Task {
      super.mvnDeps() ++ Seq(mvn"io.getkyo::kyo-core:${kyoTestVersion()}")
    }
    lazy val millDiscover = Discover[this.type]
  }

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kyotest"

  override def tests: Tests = Tests {
    test("test") - UnitTester(kyoTestModule, sourceRoot = resourcePath).scoped { eval =>
      val Right(result) = eval.apply(kyoTestModule.testForked()).runtimeChecked
      assert(result.value.results.size == 1)
      val report = eval.outPath / "testForked.dest" / "test-report.xml"
      assert(os.exists(report))
    }
    test("discoveredTestClasses") - UnitTester(kyoTestModule, sourceRoot = resourcePath).scoped {
      eval =>
        val Right(result) = eval.apply(kyoTestModule.discoveredTestClasses).runtimeChecked
        val expected = Seq("mill.scalalib.KyoTestSpec")
        assert(result.value == expected)
        expected
    }
  }
}
