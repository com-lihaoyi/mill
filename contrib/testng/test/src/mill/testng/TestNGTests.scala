package mill.testng

import mill.*
import mill.define.{Discover, Target}
import mill.scalalib.*
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*

object TestNGTests extends TestSuite {

  object demo extends TestBaseModule with JavaModule {

    object test extends JavaTests {
      override def runMvnDeps = super.runMvnDeps() ++ Seq(
        Dep.millProjectModule("mill-contrib-testng", artifactSuffix = "")
      )
      override def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"org.testng:testng:6.11",
        mvn"de.tototec:de.tobiasroeser.lambdatest:0.8.0"
      )
      override def testFramework = Task {
        "mill.testng.TestNGFramework"
      }
    }

    object testng extends JavaTests with TestModule.TestNg {
      def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"org.testng:testng:7.10.2"
      )
    }

    object testngGrouping extends JavaTests with TestModule.TestNg {
      def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"org.testng:testng:7.10.2"
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
        val Right(result) = eval.apply(demo.test.testForked()): @unchecked
        val tres = result.value
        assert(tres._2.size == 8)
    }
    test("noGrouping") - UnitTester(demo, resourcePath).scoped {
      eval =>
        val Right(result) = eval.apply(demo.testng.testForked()): @unchecked
        val tres = result.value._2
        assert(tres.map(_.fullyQualifiedName).toSet == Set("foo.HelloTests", "foo.WorldTests"))
    }
    test("testForkGrouping") - UnitTester(demo, resourcePath).scoped {
      eval =>
        val Right(result) = eval.apply(demo.testngGrouping.testForked()): @unchecked
        val tres = result.value._2
        assert(tres.map(_.fullyQualifiedName).toSet == Set("foo.HelloTests", "foo.WorldTests"))
    }
  }
}
