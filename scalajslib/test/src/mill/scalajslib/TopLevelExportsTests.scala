package mill.scalajslib

import mill.define.Discover
import mill.scalajslib.api._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object TopLevelExportsTests extends TestSuite {
  object TopLevelExportsModule extends TestBaseModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion =
      sys.props.getOrElse("TEST_SCALAJS_VERSION", ???) // at least "1.8.0"
    override def moduleKind = ModuleKind.ESModule

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "top-level-exports"

  val evaluator = UnitTester(TopLevelExportsModule, millSourcePath)

  val tests: Tests = Tests {
    test("top level exports") {
      println(evaluator(TopLevelExportsModule.sources))
      val Right(result) =
        evaluator(TopLevelExportsModule.fastLinkJS): @unchecked
      val publicModules = result.value.publicModules.toSeq
      assert(publicModules.length == 2)
      val b = publicModules(0)
      assert(b.jsFileName == "b.js")
      assert(os.exists(result.value.dest.path / "b.js"))
      assert(os.exists(result.value.dest.path / "b.js.map"))
      val a = publicModules(1)
      assert(a.jsFileName == "a.js")
      assert(os.exists(result.value.dest.path / "a.js"))
      assert(os.exists(result.value.dest.path / "a.js.map"))
    }
  }
}
