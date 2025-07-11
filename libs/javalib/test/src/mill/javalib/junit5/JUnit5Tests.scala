package mill.javalib.junit5

import mill.api.Discover
import mill.javalib.JavaModule
import mill.javalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.TokenReaders._

object JUnit5Tests extends TestSuite {

  object module extends TestRootModule with JavaModule {
    object test extends JavaTests with TestModule.Junit5
    lazy val millDiscover = Discover[this.type]
  }

  val testModuleSourcesPath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "junit5"

  def tests = Tests {
    test("discovery") {
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res = eval(module.test.discoveredTestClasses)
        assert(res.isRight)
        assert(res.toOption.get.value == Seq("qux.QuxTests"))
      }
    }
    test("execution") {
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res = eval(module.test.testForked(""))
        assert(res.isRight)
        assert(res.toOption.get.value.results.forall(_.fullyQualifiedName == "qux.QuxTests"))
      }
    }
  }
}
