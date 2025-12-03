package mill.javalib.junit4

import mill.api.Discover
import mill.javalib.JavaModule
import mill.javalib.TestModule
import mill.javalib.testrunner.TestResult
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.TokenReaders._

object JUnit4Tests extends TestSuite {

  object module extends TestRootModule with JavaModule {
    object test extends JavaTests with TestModule.Junit4 {
      def testArgsDefault = Seq("--exclude-categories=qux.FilteredTests")
    }
    lazy val millDiscover = Discover[this.type]
  }

  val testModuleSourcesPath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "junit4"

  def tests = Tests {
    test("discovery") {
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res = eval(module.test.discoveredTestClasses)
        assert(res.isRight)
        assert(res.toOption.get.value == Seq("qux.FooTests", "qux.QuxTests"))
      }
    }
    test("execution") {
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res = eval(module.test.testForked())
        assert(res.isRight)
        val qualifiedNames = res.toOption.get.value.results
        assert(qualifiedNames.nonEmpty)
        assert(qualifiedNames.forall(_.fullyQualifiedName == "qux.QuxTests.hello"))
      }
    }
  }
}
