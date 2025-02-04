package mill.scalajslib

import mill.define.Discover
import mill.scalajslib.api._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object FullLinkJSESModuleTests extends TestSuite {

  object FullLinkJSESModuleModule extends TestBaseModule {

    object fullLinkJSESModuleModule extends ScalaJSModule {
      override def scalaVersion = "2.13.4"
      override def scalaJSVersion = "1.7.0"
      override def moduleKind = ModuleKind.ESModule
    }

    override lazy val millDiscover = {
      import mill.main.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  val tests: Tests = Tests {
    test("fullLinkJS with ESModule moduleKind") - UnitTester(
      FullLinkJSESModuleModule,
      millSourcePath
    ).scoped { eval =>
      val result = eval(FullLinkJSESModuleModule.fullLinkJSESModuleModule.fullLinkJS)
      assert(result.isRight)
    }
  }
}
