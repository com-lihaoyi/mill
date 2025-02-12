package mill.exec

import mill.testkit.UnitTester
import mill.testkit.UnitTester.Result
import mill.util.TestGraphs
import mill.util.TestGraphs.{
  crossResolved,
  doubleCross,
  nestedCrosses,
  innerCrossModule,
  singleCross,
  nonStringCross,
  crossExtension
}
import utest._

object CrossTests extends TestSuite {
  val tests = Tests {
    test("singleCross") {
      val check = UnitTester(singleCross, null)

      val Right(Result("210", 1)) = check(singleCross.cross("210").suffix): @unchecked
      val Right(Result("211", 1)) = check(singleCross.cross("211").suffix): @unchecked
      val Right(Result("212", 1)) = check(singleCross.cross("212").suffix): @unchecked
    }

    test("nonStringCross") {
      val check = UnitTester(nonStringCross, null)

      val Right(Result(210, 1)) = check(nonStringCross.cross(210).suffix): @unchecked
      val Right(Result(211, 1)) = check(nonStringCross.cross(211).suffix): @unchecked
      val Right(Result(212, 1)) = check(nonStringCross.cross(212).suffix): @unchecked
    }

    test("crossExtension") {
      val check = UnitTester(crossExtension, null)

      val Right(Result("Param Value: a", _)) = check(crossExtension.myCross("a").param1): @unchecked

      val Right(Result("Param Value: a", _)) =
        check(crossExtension.myCrossExtended("a", 1).param1): @unchecked
      val Right(Result("Param Value: 2", _)) =
        check(crossExtension.myCrossExtended("b", 2).param2): @unchecked

      val Right(Result("Param Value: a", _)) =
        check(crossExtension.myCrossExtendedAgain("a", 1, true).param1): @unchecked

      val Right(Result("Param Value: 2", _)) =
        check(crossExtension.myCrossExtendedAgain("b", 2, false).param2): @unchecked

      val Right(Result("Param Value: true", _)) =
        check(crossExtension.myCrossExtendedAgain("a", 1, true).param3): @unchecked

      val Right(Result("Param Value: false", _)) =
        check(crossExtension.myCrossExtendedAgain("b", 2, false).param3): @unchecked
    }

    test("crossResolved") {
      val check = UnitTester(crossResolved, null)

      val Right(Result("2.10", 1)) = check(crossResolved.foo("2.10").suffix): @unchecked
      val Right(Result("2.11", 1)) = check(crossResolved.foo("2.11").suffix): @unchecked
      val Right(Result("2.12", 1)) = check(crossResolved.foo("2.12").suffix): @unchecked

      val Right(Result("_2.10", 1)) = check(crossResolved.bar("2.10").longSuffix): @unchecked
      val Right(Result("_2.11", 1)) = check(crossResolved.bar("2.11").longSuffix): @unchecked
      val Right(Result("_2.12", 1)) = check(crossResolved.bar("2.12").longSuffix): @unchecked
    }

    test("doubleCross") {
      val check = UnitTester(doubleCross, null)

      val Right(Result("210_jvm", 1)) = check(doubleCross.cross("210", "jvm").suffix): @unchecked
      val Right(Result("210_js", 1)) = check(doubleCross.cross("210", "js").suffix): @unchecked
      val Right(Result("211_jvm", 1)) = check(doubleCross.cross("211", "jvm").suffix): @unchecked
      val Right(Result("211_js", 1)) = check(doubleCross.cross("211", "js").suffix): @unchecked
      val Right(Result("212_jvm", 1)) = check(doubleCross.cross("212", "jvm").suffix): @unchecked
      val Right(Result("212_js", 1)) = check(doubleCross.cross("212", "js").suffix): @unchecked
      val Right(Result("212_native", 1)) =
        check(doubleCross.cross("212", "native").suffix): @unchecked
    }

    test("innerCrossModule") {
      val check = UnitTester(innerCrossModule, null)

      val Right(Result("foo a", 1)) = check(innerCrossModule.myCross("a").foo.bar): @unchecked
      val Right(Result("baz b", 1)) = check(innerCrossModule.myCross("b").baz.bar): @unchecked

      val Right(Result("foo a", 1)) = check(innerCrossModule.myCross2("a", 1).foo.bar): @unchecked
      val Right(Result("foo 1", 1)) = check(innerCrossModule.myCross2("a", 1).foo.qux): @unchecked
      val Right(Result("baz b", 1)) = check(innerCrossModule.myCross2("b", 2).baz.bar): @unchecked
      val Right(Result("baz 2", 1)) = check(innerCrossModule.myCross2("b", 2).baz.qux): @unchecked

      val Right(Result("foo a", 1)) =
        check(innerCrossModule.myCross3("a", 1, true).foo.bar): @unchecked
      val Right(Result("foo 1", 1)) =
        check(innerCrossModule.myCross3("a", 1, true).foo.qux): @unchecked
      val Right(Result("foo true", 1)) =
        check(innerCrossModule.myCross3("a", 1, true).foo.lol): @unchecked
      val Right(Result("baz b", 1)) =
        check(innerCrossModule.myCross3("b", 2, false).baz.bar): @unchecked
      val Right(Result("baz 2", 1)) =
        check(innerCrossModule.myCross3("b", 2, false).baz.qux): @unchecked
      val Right(Result("baz false", 1)) =
        check(innerCrossModule.myCross3("b", 2, false).baz.lol): @unchecked
    }

    test("nestedCrosses") {
      val check = UnitTester(nestedCrosses, null)

      val Right(Result("210_jvm", 1)) =
        check(nestedCrosses.cross("210").cross2("jvm").suffix): @unchecked
      val Right(Result("210_js", 1)) =
        check(nestedCrosses.cross("210").cross2("js").suffix): @unchecked
      val Right(Result("211_jvm", 1)) =
        check(nestedCrosses.cross("211").cross2("jvm").suffix): @unchecked
      val Right(Result("211_js", 1)) =
        check(nestedCrosses.cross("211").cross2("js").suffix): @unchecked
      val Right(Result("212_jvm", 1)) =
        check(nestedCrosses.cross("212").cross2("jvm").suffix): @unchecked
      val Right(Result("212_js", 1)) =
        check(nestedCrosses.cross("212").cross2("js").suffix): @unchecked
      val Right(Result("212_native", 1)) =
        check(nestedCrosses.cross("212").cross2("native").suffix): @unchecked
    }

    test("nestedTaskCrosses") {
      val model = TestGraphs.nestedTaskCrosses
      val check = UnitTester(model, null)

      val Right(Result("210_jvm_1", 1)) =
        check(model.cross1("210").cross2("jvm").suffixCmd("1")): @unchecked
      val Right(Result("210_js_2", 1)) =
        check(model.cross1("210").cross2("js").suffixCmd("2")): @unchecked
      val Right(Result("211_jvm_3", 1)) =
        check(model.cross1("211").cross2("jvm").suffixCmd("3")): @unchecked
      val Right(Result("211_js_4", 1)) =
        check(model.cross1("211").cross2("js").suffixCmd("4")): @unchecked
      val Right(Result("212_jvm_5", 1)) =
        check(model.cross1("212").cross2("jvm").suffixCmd("5")): @unchecked
      val Right(Result("212_js_6", 1)) =
        check(model.cross1("212").cross2("js").suffixCmd("6")): @unchecked
      val Right(Result("212_native_7", 1)) =
        check(model.cross1("212").cross2("native").suffixCmd("7")): @unchecked
    }
  }
}
