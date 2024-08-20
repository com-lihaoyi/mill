package mill
package testng

import mill.define.Target
import mill.util.Util.millProjectModule
import mill.scalalib._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object TestNGTests extends TestSuite {

  object demo extends TestBaseModule with JavaModule {

    object test extends JavaModuleTests {
      def testngClasspath = T {
        millProjectModule(
          "mill-contrib-testng",
          repositoriesTask(),
          artifactSuffix = ""
        )
      }

      override def runClasspath: Target[Seq[PathRef]] =
        T { super.runClasspath() ++ testngClasspath() }
      override def ivyDeps = T {
        super.ivyDeps() ++
          Agg(
            ivy"org.testng:testng:6.11",
            ivy"de.tototec:de.tobiasroeser.lambdatest:0.8.0"
          )
      }
      override def testFramework = T {
        "mill.testng.TestNGFramework"
      }
    }

  }

  val resourcePath: os.Path = os.pwd / "contrib" / "testng" / "test" / "resources" / "demo"

  def workspaceTest[T, M <: mill.testkit.TestBaseModule](
      m: M,
      resourcePath: os.Path = resourcePath
  )(t: UnitTester => T)(implicit tp: TestPath): T = {
    val eval = new UnitTester(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    test("TestNG") {
      test("demo") - workspaceTest(demo) { eval =>
        val Right(result) = eval.apply(demo.test.testFramework)
        assert(
          result.value == "mill.testng.TestNGFramework",
          result.evalCount > 0
        )
      }
      test("Test case lookup from inherited annotations") - workspaceTest(demo) { eval =>
        val Right(result) = eval.apply(demo.test.test())
        val tres = result.value.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
        assert(
          tres._2.size == 8
        )
      }
    }
  }
}
