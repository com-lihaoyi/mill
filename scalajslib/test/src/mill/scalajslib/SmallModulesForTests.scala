package mill.scalajslib

import mill.define.Discover
import mill.scalajslib.api._
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object SmallModulesForTests extends TestSuite {
  object SmallModulesForModule extends TestBaseModule with ScalaJSModule {

    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion =
      sys.props.getOrElse("TEST_SCALAJS_VERSION", ???) // at least "1.10.0"
    override def moduleKind = ModuleKind.ESModule
    override def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor(List("app"))

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "small-modules-for"

  val evaluator = UnitTester(SmallModulesForModule, millSourcePath)

  val tests: Tests = Tests {
    test("ModuleSplitStyle.SmallModulesFor") {
      println(evaluator(SmallModulesForModule.sources))

      val Right(result) = evaluator(SmallModulesForModule.fastLinkJS): @unchecked
      val publicModules = result.value.publicModules
      test("it should have a single publicModule") {
        assert(publicModules.size == 1)
      }
      test("my.Foo should not have its own file since it is in a separate package") {
        assert(!os.exists(result.value.dest.path / "otherpackage.Foo.js"))
      }
      println(os.list(result.value.dest.path))
      val modulesLength = os.list(result.value.dest.path).length

      // this changed from 10 to 8 after Scala JS version 1.13
      assert(modulesLength == 8)
    }
  }
}
