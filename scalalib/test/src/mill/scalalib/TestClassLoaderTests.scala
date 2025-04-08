package mill.scalalib

import mill.define.Discover
import mill.Task
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.util.TokenReaders._
import utest.*

object TestClassLoaderTests extends TestSuite {
  object testclassloader extends TestBaseModule with ScalaModule {
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object test extends ScalaTests with TestModule.Utest {
      override def ivyDeps = Task {
        super.ivyDeps() ++ Seq(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
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
