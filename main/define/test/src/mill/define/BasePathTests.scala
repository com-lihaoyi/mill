package mill.define

import mill.util.TestGraphs
import mill.testkit.TestBaseModule
import utest._

object BasePathTests extends TestSuite {

  object overriddenBasePath extends TestBaseModule {
    override def millSourcePath = os.pwd / "overriddenBasePathRootValue"
    object nested extends Module {
      override def millSourcePath = super.millSourcePath / "overriddenBasePathNested"
      object nested extends Module {
        override def millSourcePath = super.millSourcePath / "overriddenBasePathDoubleNested"
      }
    }
  }

  val testGraphs = new TestGraphs
  val tests = Tests {
    def checkMillSourcePath[T <: Module](m: T)(f: T => Module, segments: String*): Unit = {
      val sub = f(m)
      val remaining = sub.millSourcePath.relativeTo(m.millSourcePath).segments
      assert(remaining == segments)
    }
    test("singleton") {
      checkMillSourcePath(testGraphs.singleton)(identity)
    }
    test("backtickIdentifiers") {
      checkMillSourcePath(testGraphs.bactickIdentifiers)(
        _.`nested-module`,
        "nested-module"
      )
    }
    test("separateGroups") {
      checkMillSourcePath(TestGraphs.triangleTask)(identity)
    }
    test("TraitWithModuleObject") {
      checkMillSourcePath(TestGraphs.TraitWithModuleObject)(
        _.TraitModule,
        "TraitModule"
      )
    }
    test("nestedModuleNested") {
      checkMillSourcePath(TestGraphs.nestedModule)(_.nested, "nested")
    }
    test("nestedModuleInstance") {
      checkMillSourcePath(TestGraphs.nestedModule)(_.classInstance, "classInstance")
    }
    test("singleCross") {
      checkMillSourcePath(TestGraphs.singleCross)(_.cross, "cross")
      checkMillSourcePath(TestGraphs.singleCross)(_.cross("210"), "cross")
      checkMillSourcePath(TestGraphs.singleCross)(_.cross("211"), "cross")
      checkMillSourcePath(TestGraphs.singleCross)(_.cross2, "cross2")
      checkMillSourcePath(TestGraphs.singleCross)(_.cross2("210"), "cross2", "210")
      checkMillSourcePath(TestGraphs.singleCross)(_.cross2("211"), "cross2", "211")
    }
    test("doubleCross") {
      checkMillSourcePath(TestGraphs.doubleCross)(_.cross, "cross")
      checkMillSourcePath(TestGraphs.doubleCross)(_.cross("210", "jvm"), "cross")
      checkMillSourcePath(TestGraphs.doubleCross)(_.cross("212", "js"), "cross")
    }
    test("nestedCrosses") {
      checkMillSourcePath(TestGraphs.nestedCrosses)(_.cross, "cross")
      checkMillSourcePath(TestGraphs.nestedCrosses)(_.cross("210").cross2("js"), "cross", "cross2")
    }
    test("overridden") {
      assert(
        overriddenBasePath.millSourcePath == os.pwd / "overriddenBasePathRootValue",
        overriddenBasePath.nested.millSourcePath == os.pwd / "overriddenBasePathRootValue/nested/overriddenBasePathNested",
        overriddenBasePath.nested.nested.millSourcePath == os.pwd / "overriddenBasePathRootValue/nested/overriddenBasePathNested/nested/overriddenBasePathDoubleNested"
      )
    }

  }
}
