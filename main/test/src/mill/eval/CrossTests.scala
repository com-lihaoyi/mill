package mill.eval

import mill.define.Discover
import mill.util.{TestEvaluator, TestGraphs}
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
    "singleCross" - {
      val check = new TestEvaluator(singleCross)

      val Right(("210", 1)) = check.apply(singleCross.cross("210").suffix)
      val Right(("211", 1)) = check.apply(singleCross.cross("211").suffix)
      val Right(("212", 1)) = check.apply(singleCross.cross("212").suffix)
    }

    "nonStringCross" - {
      val check = new TestEvaluator(nonStringCross)

      val Right((210, 1)) = check.apply(nonStringCross.cross(210).suffix)
      val Right((211, 1)) = check.apply(nonStringCross.cross(211).suffix)
      val Right((212, 1)) = check.apply(nonStringCross.cross(212).suffix)
    }

    "crossExtension" - {
      val check = new TestEvaluator(crossExtension)

      val Right(("Param Value: a", _)) = check.apply(crossExtension.myCross("a").param1)

      val Right(("Param Value: a", _)) = check.apply(crossExtension.myCrossExtended("a", 1).param1)
      val Right(("Param Value: 2", _)) = check.apply(crossExtension.myCrossExtended("b", 2).param2)

      val Right(("Param Value: a", _)) =
        check.apply(crossExtension.myCrossExtendedAgain("a", 1, true).param1)
      val Right(("Param Value: 2", _)) =
        check.apply(crossExtension.myCrossExtendedAgain("b", 2, false).param2)
      val Right(("Param Value: true", _)) =
        check.apply(crossExtension.myCrossExtendedAgain("a", 1, true).param3)
      val Right(("Param Value: false", _)) =
        check.apply(crossExtension.myCrossExtendedAgain("b", 2, false).param3)
    }

    "crossResolved" - {
      val check = new TestEvaluator(crossResolved)

      val Right(("2.10", 1)) = check.apply(crossResolved.foo("2.10").suffix)
      val Right(("2.11", 1)) = check.apply(crossResolved.foo("2.11").suffix)
      val Right(("2.12", 1)) = check.apply(crossResolved.foo("2.12").suffix)

      val Right(("_2.10", 1)) = check.apply(crossResolved.bar("2.10").longSuffix)
      val Right(("_2.11", 1)) = check.apply(crossResolved.bar("2.11").longSuffix)
      val Right(("_2.12", 1)) = check.apply(crossResolved.bar("2.12").longSuffix)
    }

    "doubleCross" - {
      val check = new TestEvaluator(doubleCross)

      val Right(("210_jvm", 1)) = check.apply(doubleCross.cross("210", "jvm").suffix)
      val Right(("210_js", 1)) = check.apply(doubleCross.cross("210", "js").suffix)
      val Right(("211_jvm", 1)) = check.apply(doubleCross.cross("211", "jvm").suffix)
      val Right(("211_js", 1)) = check.apply(doubleCross.cross("211", "js").suffix)
      val Right(("212_jvm", 1)) = check.apply(doubleCross.cross("212", "jvm").suffix)
      val Right(("212_js", 1)) = check.apply(doubleCross.cross("212", "js").suffix)
      val Right(("212_native", 1)) = check.apply(doubleCross.cross("212", "native").suffix)
    }

    "innerCrossModule" - {
      val check = new TestEvaluator(innerCrossModule)

      val Right(("foo a", 1)) = check.apply(innerCrossModule.myCross("a").foo.bar)
      val Right(("baz b", 1)) = check.apply(innerCrossModule.myCross("b").baz.bar)

      val Right(("foo a", 1)) = check.apply(innerCrossModule.myCross2("a", 1).foo.bar)
      val Right(("foo 1", 1)) = check.apply(innerCrossModule.myCross2("a", 1).foo.qux)
      val Right(("baz b", 1)) = check.apply(innerCrossModule.myCross2("b", 2).baz.bar)
      val Right(("baz 2", 1)) = check.apply(innerCrossModule.myCross2("b", 2).baz.qux)

      val Right(("foo a", 1)) = check.apply(innerCrossModule.myCross3("a", 1, true).foo.bar)
      val Right(("foo 1", 1)) = check.apply(innerCrossModule.myCross3("a", 1, true).foo.qux)
      val Right(("foo true", 1)) = check.apply(innerCrossModule.myCross3("a", 1, true).foo.lol)
      val Right(("baz b", 1)) = check.apply(innerCrossModule.myCross3("b", 2, false).baz.bar)
      val Right(("baz 2", 1)) = check.apply(innerCrossModule.myCross3("b", 2, false).baz.qux)
      val Right(("baz false", 1)) = check.apply(innerCrossModule.myCross3("b", 2, false).baz.lol)
    }

    "nestedCrosses" - {
      val check = new TestEvaluator(nestedCrosses)

      val Right(("210_jvm", 1)) = check.apply(nestedCrosses.cross("210").cross2("jvm").suffix)
      val Right(("210_js", 1)) = check.apply(nestedCrosses.cross("210").cross2("js").suffix)
      val Right(("211_jvm", 1)) = check.apply(nestedCrosses.cross("211").cross2("jvm").suffix)
      val Right(("211_js", 1)) = check.apply(nestedCrosses.cross("211").cross2("js").suffix)
      val Right(("212_jvm", 1)) = check.apply(nestedCrosses.cross("212").cross2("jvm").suffix)
      val Right(("212_js", 1)) = check.apply(nestedCrosses.cross("212").cross2("js").suffix)
      val Right(("212_native", 1)) = check.apply(nestedCrosses.cross("212").cross2("native").suffix)
    }

    "nestedTaskCrosses" - {
      val model = TestGraphs.nestedTaskCrosses
      val check = new TestEvaluator(model)

      val Right(("210_jvm_1", 1)) = check.apply(model.cross1("210").cross2("jvm").suffixCmd("1"))
      val Right(("210_js_2", 1)) = check.apply(model.cross1("210").cross2("js").suffixCmd("2"))
      val Right(("211_jvm_3", 1)) = check.apply(model.cross1("211").cross2("jvm").suffixCmd("3"))
      val Right(("211_js_4", 1)) = check.apply(model.cross1("211").cross2("js").suffixCmd("4"))
      val Right(("212_jvm_5", 1)) = check.apply(model.cross1("212").cross2("jvm").suffixCmd("5"))
      val Right(("212_js_6", 1)) = check.apply(model.cross1("212").cross2("js").suffixCmd("6"))
      val Right(("212_native_7", 1)) =
        check.apply(model.cross1("212").cross2("native").suffixCmd("7"))
    }
  }
}
