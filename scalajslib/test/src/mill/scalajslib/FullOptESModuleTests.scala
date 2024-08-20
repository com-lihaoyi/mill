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

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val fullOptESModuleModuleEvaluator = UnitTester.static(FullOptESModuleModule)

  val tests: Tests = Tests {
    test("fullOpt with ESModule moduleKind") {
      val result =
        fullOptESModuleModuleEvaluator(FullOptESModuleModule.fullOptESModuleModule.fullOpt)
      assert(result.isRight)
    }
  }
}
