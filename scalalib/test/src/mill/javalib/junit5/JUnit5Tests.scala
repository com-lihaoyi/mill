package mill.javalib.junit5

import mill.scalalib.JavaModule
import mill.scalalib.TestModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object JUnit5Tests extends TestSuite {

  object module extends TestBaseModule with JavaModule {
    object test extends JavaTests with TestModule.Junit5
  }

  val testModuleSourcesPath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "junit5"

  def tests = Tests {
    test("discovery") {
      val eval = UnitTester(module, testModuleSourcesPath)
      val res = eval(module.test.discoveredTestClasses)
      assert(res.isRight)
      assert(res.toOption.get.value == Seq("qux.QuxTests"))
    }
    test("execution") {
      val eval = UnitTester(module, testModuleSourcesPath)
      val res = eval(module.test.test(""))
      assert(res.isRight)
      assert(res.toOption.get.value._2.forall(_.fullyQualifiedName == "qux.QuxTests"))
    }
  }
}
