package mill.define

import mill.util.TestGraphs
import utest._
import ammonite.ops._
object BasePathTests extends TestSuite{
  val testGraphs = new TestGraphs
  val tests = Tests{
    def check(m: Module, segments: String*) = {
      val remaining = m.basePath.relativeTo(pwd).segments.drop(1)
      assert(remaining == segments)
    }
    'singleton - {
      check(testGraphs.singleton, "singleton")
      check(testGraphs.singleton, "singleton")

    }
    'separateGroups - {
      check(TestGraphs.separateGroups, "separateGroups")
    }
    'TraitWithModuleObject - {
      check(TestGraphs.TraitWithModuleObject.TraitModule,
       "TraitWithModuleObject", "TraitModule"
      )
    }
    'nestedModuleNested - {
      check(TestGraphs.nestedModule.nested, "nestedModule", "nested")
    }
    'nestedModuleInstance - {
      check(TestGraphs.nestedModule.classInstance,  "nestedModule", "classInstance")
    }
    'singleCross - {
      check(TestGraphs.singleCross.cross, "singleCross", "cross")
      check(TestGraphs.singleCross.cross("210"), "singleCross", "cross", "210")
      check(TestGraphs.singleCross.cross("211"), "singleCross", "cross", "211")
    }
    'doubleCross - {
      check(TestGraphs.doubleCross.cross, "doubleCross", "cross")
      check(TestGraphs.doubleCross.cross("210", "jvm"), "doubleCross", "cross", "210", "jvm")
      check(TestGraphs.doubleCross.cross("212", "js"), "doubleCross", "cross", "212", "js")
    }
    'nestedCrosses - {
      check(TestGraphs.nestedCrosses.cross, "nestedCrosses", "cross")
      check(
        TestGraphs.nestedCrosses.cross("210").cross2("js"),
        "nestedCrosses", "cross", "210", "cross2", "js"
      )
    }

  }
}

