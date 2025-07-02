package mill.api

import mill.api.TestGraphs
import Task.Simple
import utest._

object DiscoverTests extends TestSuite {
  val tests = Tests {
    def check[T <: Module](m: T)(tasks: (T => Simple[?])*) = {
      val discovered = m.moduleInternal.simpleTasks
      val expected = tasks.map(_(m)).toSet
      assert(discovered == expected)
    }
    test("singleton") {
      check(TestGraphs.singleton)(_.single)
    }
    test("backtickIdentifiers") {
      check(TestGraphs.bactickIdentifiers)(
        _.`up-task`,
        _.`a-down-task`,
        _.`nested-module`.`nested-task`
      )
    }
    test("separateGroups") {
      check(TestGraphs.triangleTask)(_.left, _.right)
    }
    test("TraitWithModuleObject") {
      check(TestGraphs.TraitWithModuleObject)(_.TraitModule.testFrameworks)
    }
    test("nestedModule") {
      check(TestGraphs.nestedModule)(_.single, _.nested.single, _.classInstance.single)
    }
    test("singleCross") {
      check(TestGraphs.singleCross)(
        _.cross("210").suffix,
        _.cross("211").suffix,
        _.cross("212").suffix,
        _.cross2("210").suffix,
        _.cross2("211").suffix,
        _.cross2("212").suffix
      )
    }
    test("doubleCross") {
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
    test("nestedCrosses") {
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
