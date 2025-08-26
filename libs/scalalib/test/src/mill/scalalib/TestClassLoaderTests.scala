package mill.scalalib

import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import mill.util.TokenReaders.*
import utest.*

object TestClassLoaderTests extends TestSuite {

  object testclassloader extends TestRootModule with ScalaModule {
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object test extends ScalaTests with TestModule.Utest {
      override def utestVersion = sys.props.getOrElse("TEST_UTEST_VERSION", ???)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "classloader-test"

  override def tests: Tests = Tests {
    test("com.sun classes exist in tests classpath (Java 8 only)") - UnitTester(
      testclassloader,
      resourcePath
    ).scoped { eval =>
      assert(eval.apply(testclassloader.test.testForked()).isRight)
    }
  }
}
