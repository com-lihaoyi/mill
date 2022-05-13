package mill.scalalib

import mill.{Agg, T}

import scala.util.Success
import mill.testrunner.TestRunner.TestArgs
import mill.util.{TestEvaluator, TestUtil}
import org.scalacheck.Prop.forAll
import utest._
import utest.framework.TestPath

object TestClassLoaderTests extends TestSuite {
  object testclassloader extends TestUtil.BaseModule with ScalaModule {
    override def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)

    object test extends super.Tests with TestModule.Utest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "classloader-test"

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m)
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
