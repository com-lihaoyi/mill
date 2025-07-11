package mill.scalajslib

import mill.api.Discover
import mill.scalajslib.api._
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest._

object SmallModulesForTests extends TestSuite {
  object SmallModulesForModule extends TestRootModule with ScalaJSModule {

    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion =
      sys.props.getOrElse("TEST_SCALAJS_VERSION", ???) // at least "1.10.0"
    override def moduleKind = ModuleKind.ESModule
    override def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor("app")

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "small-modules-for"

  val tests: Tests = Tests {
    test("ModuleSplitStyle.SmallModulesFor") {
      UnitTester(SmallModulesForModule, millSourcePath).scoped { evaluator =>
        println(evaluator(SmallModulesForModule.sources))

        val Right(result) = evaluator(SmallModulesForModule.fastLinkJS): @unchecked
        val publicModules = result.value.publicModules

        println(os.list(result.value.dest.path))
        val modulesLength = os.list(result.value.dest.path).length

        // this changed from 10 to 8 after Scala JS version 1.13
        assert(modulesLength == 8)
        assert(publicModules.size == 1)
        assert(!os.exists(result.value.dest.path / "otherpackage.Foo.js"))
      }
    }
  }
}
