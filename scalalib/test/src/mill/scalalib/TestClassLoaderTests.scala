package mill.scalalib

import mill.{Agg, T}

import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import utest.framework.TestPath

object TestClassLoaderTests extends TestSuite {
  object testclassloader extends TestBaseModule with ScalaModule {
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object test extends ScalaTests with TestModule.Utest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "classloader-test"

  def workspaceTest[T](
                        m: mill.testkit.TestBaseModule,
                        resourcePath: os.Path = resourcePath
  )(t: UnitTester => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new UnitTester(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  override def tests: Tests = Tests {
    test("com.sun classes exist in tests classpath (Java 8 only)") {
      workspaceTest(testclassloader) { eval =>
        assert(eval.apply(testclassloader.test.test()).isRight)
      }
    }
  }
}
