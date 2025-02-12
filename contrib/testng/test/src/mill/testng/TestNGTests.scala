package mill
package testng

import mill.define.{Discover, Target}
import mill.util.MillModuleUtil.millProjectModule
import mill.scalalib.*
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*

object TestNGTests extends TestSuite {

  object demo extends TestBaseModule with JavaModule {

    object test extends JavaTests {
      def testngClasspath = Task {
        millProjectModule(
          "mill-contrib-testng",
          repositoriesTask(),
          artifactSuffix = ""
        )
      }

      override def runClasspath: T[Seq[PathRef]] =
        Task { super.runClasspath() ++ testngClasspath() }
      override def ivyDeps = Task {
        super.ivyDeps() ++
          Seq(
            ivy"org.testng:testng:6.11",
            ivy"de.tototec:de.tobiasroeser.lambdatest:0.8.0"
          )
      }
      override def testFramework = Task {
        "mill.testng.TestNGFramework"
      }
    }

    object testng extends JavaTests with TestModule.TestNg {
      def ivyDeps = super.ivyDeps() ++ Seq(
        ivy"org.testng:testng:7.10.2"
      )
    }

    object testngGrouping extends JavaTests with TestModule.TestNg {
      def ivyDeps = super.ivyDeps() ++ Seq(
        ivy"org.testng:testng:7.10.2"
      )
      def testForkGrouping = discoveredTestClasses().grouped(1).toSeq
    }

    lazy val millDiscover = Discover[this.type]
  }
  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "demo"

  def tests: Tests = Tests {
    test("demo") - UnitTester(demo, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(demo.test.testFramework): @unchecked
      assert(
        result.value == "mill.testng.TestNGFramework",
        result.evalCount > 0
      )
    }
    test("Test case lookup from inherited annotations") - UnitTester(demo, resourcePath).scoped {
      eval =>
        val Right(result) = eval.apply(demo.test.test()): @unchecked
        val tres = result.value
        assert(tres._2.size == 8)
    }
    test("noGrouping") - UnitTester(demo, resourcePath).scoped {
      eval =>
        val Right(result) = eval.apply(demo.testng.test()): @unchecked
        val tres = result.value._2
        assert(tres.map(_.fullyQualifiedName).toSet == Set("foo.HelloTests", "foo.WorldTests"))
    }
    test("testForkGrouping") - UnitTester(demo, resourcePath).scoped {
      eval =>
        val Right(result) = eval.apply(demo.testngGrouping.test()): @unchecked
        val tres = result.value._2
        assert(tres.map(_.fullyQualifiedName).toSet == Set("foo.HelloTests", "foo.WorldTests"))
    }
  }
}
