package mill
package testng

import mill.api.Result.Exception
import mill.define.Target
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object TestNGTests extends TestSuite {

  object demo extends TestUtil.BaseModule with JavaModule {
    override def millSourcePath: os.Path =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    object test extends super.Tests {
      def testngClasspath = T {
        mill.modules.Util.millProjectModule("MILL_TESTNG_LIB", "mill-contrib-testng", repositoriesTask())
      }

      override def runClasspath: Target[Seq[PathRef]] =
        T { super.runClasspath() ++ testngClasspath() }
      override def ivyDeps = T {
        super.ivyDeps() ++
          Agg(
            ivy"org.testng:testng:6.11",
            ivy"de.tototec:de.tobiasroeser.lambdatest:0.7.1"
          )
      }
      override def testFramework = T {
        "mill.testng.TestNGFramework"
      }
    }

  }

  val resourcePath: os.Path = os.pwd / "contrib" / "testng" / "test" / "resources" / "demo"

  def workspaceTest[T, M <: TestUtil.BaseModule](
      m: M,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    "TestNG" - {
      "demo" - workspaceTest(demo) { eval =>
        val Right((result, evalCount)) = eval.apply(demo.test.testFramework)
        assert(
          result == "mill.testng.TestNGFramework",
          evalCount > 0
        )
      }
      "Test case lookup from inherited annotations" - workspaceTest(demo) { eval =>
        val Right((result, evalCount)) = eval.apply(demo.test.test())
        val tres = result.asInstanceOf[(String, Seq[mill.testrunner.TestRunner.Result])]
        assert(
          tres._2.size == 8
        )
      }
    }
  }
}
