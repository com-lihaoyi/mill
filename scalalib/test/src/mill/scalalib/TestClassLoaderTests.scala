package mill.scalalib

import mill.{Agg, T}

import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath
import mill.api.PathRef

object TestClassLoaderTests extends TestSuite {
  object testclassloader extends TestUtil.BaseModule with ScalaModule {
    override def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object utest extends ScalaTests with TestModule.Utest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }

    object scalatest extends ScalaTests with TestModule.ScalaTest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"org.scalatest::scalatest:${sys.props.getOrElse("TEST_SCALATEST_VERSION", ???)}"
        )
      }
    }

    object ziotest extends ScalaTests with TestModule.ZioTest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"dev.zio::zio-test:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}"
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

  private val sbtTesting = "org/scala-sbt/test-interface/"

  private val testFramework = List(
    ("utest", testclassloader.utest),
    ("ziotest", testclassloader.ziotest),
    ("scalatest", testclassloader.scalatest)
  )

  def testSbtInterfaceInClassPath(
      classpath: TestModule
  )(implicit path: utest.framework.TestPath) = {
    workspaceTest(testclassloader) { eval =>
      val classpathElements = eval.apply(classpath.runClasspath).toOption.get._1
      assert(classpathElements.exists(_.toString.contains(sbtTesting)))
    }
  }

  override def tests: Tests = Tests {

    test("com.sun classes exist in tests classpath (Java 8 only)") {
      workspaceTest(testclassloader) { eval =>
        assert(eval.apply(testclassloader.utest.test()).isRight)
      }
    }

    test("test-interface must be present in utest classpath") {
      testSbtInterfaceInClassPath(testclassloader.utest)
    }

    test("test-interface must be present in ziotest classpath") {
      testSbtInterfaceInClassPath(testclassloader.ziotest)
    }

    test("test-interface must be present in scalatest classpath") {
      testSbtInterfaceInClassPath(testclassloader.scalatest)
    }
  }
}
