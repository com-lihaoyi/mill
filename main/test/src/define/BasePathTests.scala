package mill.define

import mill.util.{TestGraphs, TestUtil}
import utest._
import mill.{Module, T}
object BasePathTests extends TestSuite{
  val testGraphs = new TestGraphs
  val tests = Tests{
    def check[T <: Module](m: T)(f: T => Module, segments: String*) = {
      val remaining = f(m).millSourcePath.relativeTo(m.millSourcePath).segments
      assert(remaining == segments)
    }
    'singleton - {
      check(testGraphs.singleton)(identity)
    }
    'backtickIdentifiers - {
      check(testGraphs.bactickIdentifiers)(
        _.`nested-module`,
        "nested-module"
      )
    }
    'separateGroups - {
      check(TestGraphs.triangleTask)(identity)
    }
    'TraitWithModuleObject - {
      check(TestGraphs.TraitWithModuleObject)(
        _.TraitModule,
       "TraitModule"
      )
    }
    'nestedModuleNested - {
      check(TestGraphs.nestedModule)(_.nested, "nested")
    }
    'nestedModuleInstance - {
      check(TestGraphs.nestedModule)(_.classInstance, "classInstance")
    }
    'singleCross - {
      check(TestGraphs.singleCross)(_.cross, "cross")
      check(TestGraphs.singleCross)(_.cross("210"), "cross", "210")
      check(TestGraphs.singleCross)(_.cross("211"), "cross", "211")
    }
    'doubleCross - {
      check(TestGraphs.doubleCross)(_.cross, "cross")
      check(TestGraphs.doubleCross)(_.cross("210", "jvm"), "cross", "210", "jvm")
      check(TestGraphs.doubleCross)(_.cross("212", "js"), "cross", "212", "js")
    }
    'nestedCrosses - {
      check(TestGraphs.nestedCrosses)(_.cross, "cross")
      check(TestGraphs.nestedCrosses)(
        _.cross("210").cross2("js"),
        "cross", "210", "cross2", "js"
      )
    }
    'overriden - {
      object overridenBasePath extends TestUtil.BaseModule {
        override def millSourcePath = os.pwd / 'overridenBasePathRootValue
        object nested extends Module{
          override def millSourcePath = super.millSourcePath / 'overridenBasePathNested
          object nested extends Module{
            override def millSourcePath = super.millSourcePath / 'overridenBasePathDoubleNested
          }
        }
      }
      assert(
        overridenBasePath.millSourcePath == os.pwd / 'overridenBasePathRootValue,
        overridenBasePath.nested.millSourcePath == os.pwd / 'overridenBasePathRootValue / 'nested / 'overridenBasePathNested,
        overridenBasePath.nested.nested.millSourcePath == os.pwd / 'overridenBasePathRootValue / 'nested / 'overridenBasePathNested / 'nested / 'overridenBasePathDoubleNested
      )
    }

  }
}

