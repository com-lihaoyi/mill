package mill.scalalib

import mill.{Agg, T}

import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object TestRunnerTests extends TestSuite {
  object testrunner extends TestUtil.BaseModule with ScalaModule {
    override def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object test extends ScalaTests with TestModule.Utest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.pwd / "scalalib" / "test" / "resources" / "testrunner"

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
    "TestRunner" - {
      "test case lookup" - workspaceTest(testrunner) { eval =>
        val Right((result, _)) = eval.apply(testrunner.test.test())
        val test = result.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
        assert(
          test._2.size == 3
        )
      }
      "testOnly" - {
        def testOnly(eval: TestEvaluator, args: Seq[String], size: Int) = {
          val Right((result1, _)) = eval.apply(testrunner.test.testOnly(args: _*))
          val testOnly = result1.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
          assert(
            testOnly._2.size == size
          )
        }
        "suffix" - workspaceTest(testrunner) { eval =>
          testOnly(eval, Seq("*arTests"), 2)
        }
        "prefix" - workspaceTest(testrunner) { eval =>
          testOnly(eval, Seq("mill.scalalib.FooT*"), 1)
        }
        "exactly" - workspaceTest(testrunner) { eval =>
          testOnly(eval, Seq("mill.scalalib.FooTests"), 1)
        }
        "multi" - workspaceTest(testrunner) { eval =>
          testOnly(eval, Seq("*Bar*", "*bar*"), 2)
        }
      }
    }
  }
}
