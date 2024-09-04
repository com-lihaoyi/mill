package mill.scalajslib

import mill.define.Discover
import mill.scalajslib.api._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object FullOptESModuleTests extends TestSuite {

  object FullOptESModuleModule extends TestBaseModule {

    object fullOptESModuleModule extends ScalaJSModule {
      override def scalaVersion = "2.13.4"
      override def scalaJSVersion = "1.7.0"
      override def moduleKind = ModuleKind.ESModule
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "hello-js-world"

  val tests: Tests = Tests {
    test("fullOpt with ESModule moduleKind") {
      val eval = UnitTester(FullOptESModuleModule, millSourcePath)
      val result = eval(FullOptESModuleModule.fullOptESModuleModule.fullOpt)
      assert(result.isRight)
    }
  }
}
