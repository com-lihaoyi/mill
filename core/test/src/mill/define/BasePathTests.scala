package mill.define

import mill.util.TestGraphs
import utest._
import ammonite.ops._
object BasePathTests extends TestSuite {
  val testGraphs = new TestGraphs
  val tests = Tests {
    def check(m: Module, segments: String*) = {
      val remaining = m.basePath.relativeTo(pwd).segments.drop(1)
      assert(remaining == segments)
    }
    'singleton - {
      check(testGraphs.singleton)
    }
    'separateGroups - {
      check(TestGraphs.triangleTask)
    }
    'TraitWithModuleObject - {
      check(TestGraphs.TraitWithModuleObject.TraitModule, "TraitModule")
    }
    'nestedModuleNested - {
      check(TestGraphs.nestedModule.nested, "nested")
    }
    'nestedModuleInstance - {
      check(TestGraphs.nestedModule.classInstance, "classInstance")
    }
    'singleCross - {
      check(TestGraphs.singleCross.cross, "cross")
      check(TestGraphs.singleCross.cross("210"), "cross", "210")
      check(TestGraphs.singleCross.cross("211"), "cross", "211")
    }
    'doubleCross - {
      check(TestGraphs.doubleCross.cross, "cross")
      check(TestGraphs.doubleCross.cross("210", "jvm"), "cross", "210", "jvm")
      check(TestGraphs.doubleCross.cross("212", "js"), "cross", "212", "js")
    }
    'nestedCrosses - {
      check(TestGraphs.nestedCrosses.cross, "cross")
      check(
        TestGraphs.nestedCrosses.cross("210").cross2("js"),
        "cross",
        "210",
        "cross2",
        "js"
      )
    }

  }
}
