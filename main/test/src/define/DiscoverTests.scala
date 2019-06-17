package mill.define

import mill.util.TestGraphs
import utest._

object DiscoverTests extends TestSuite{
  val testGraphs = new TestGraphs
  val tests = Tests{
    def check[T <: Module](m: T)(targets: (T => Target[_])*) = {
      val discovered = m.millInternal.targets
      val expected = targets.map(_(m)).toSet
      assert(discovered == expected)
    }
    test("singleton"){
      check(testGraphs.singleton)(_.single)
    }
    test("backtickIdentifiers"){
      check(testGraphs.bactickIdentifiers)(_.`up-target`, _.`a-down-target`, _.`nested-module`.`nested-target`)
    }
    test("separateGroups"){
      check(TestGraphs.triangleTask)(_.left, _.right)
    }
    test("TraitWithModuleObject"){
      check(TestGraphs.TraitWithModuleObject)(_.TraitModule.testFrameworks)
    }
    test("nestedModule"){
      check(TestGraphs.nestedModule)(_.single, _.nested.single, _.classInstance.single)
    }
    test("singleCross"){
      check(TestGraphs.singleCross)(
        _.cross("210").suffix,
        _.cross("211").suffix,
        _.cross("212").suffix
      )
    }
    test("doubleCross"){
      check(TestGraphs.doubleCross)(
        _.cross("210", "jvm").suffix,
        _.cross("210", "js").suffix,
        _.cross("211", "jvm").suffix,
        _.cross("211", "js").suffix,
        _.cross("212", "jvm").suffix,
        _.cross("212", "js").suffix,
        _.cross("212", "native").suffix
      )
    }
    test("nestedCrosses"){
      check(TestGraphs.nestedCrosses)(
        _.cross("210").cross2("jvm").suffix,
        _.cross("210").cross2("js").suffix,
        _.cross("210").cross2("native").suffix,
        _.cross("211").cross2("jvm").suffix,
        _.cross("211").cross2("js").suffix,
        _.cross("211").cross2("native").suffix,
        _.cross("212").cross2("jvm").suffix,
        _.cross("212").cross2("js").suffix,
        _.cross("212").cross2("native").suffix
      )
    }

  }
}

