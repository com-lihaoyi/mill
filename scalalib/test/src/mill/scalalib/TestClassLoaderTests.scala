package mill.scalalib

import mill.{Agg, T, Task}

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import utest.framework.TestPath

object TestClassLoaderTests extends TestSuite {
  object testclassloader extends TestBaseModule with ScalaModule {
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object test extends ScalaTests with TestModule.Utest {
      override def ivyDeps = Task {
        super.ivyDeps() ++ Agg(
          mvn"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "classloader-test"

  override def tests: Tests = Tests {
    test("com.sun classes exist in tests classpath (Java 8 only)") - UnitTester(
      testclassloader,
      resourcePath
    ).scoped { eval =>
      assert(eval.apply(testclassloader.test.test()).isRight)
    }
  }
}
