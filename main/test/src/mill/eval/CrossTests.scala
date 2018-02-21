package mill.eval

import ammonite.ops._
import mill.define.Discover
import mill.util.TestEvaluator

import mill.util.TestGraphs.{crossResolved, doubleCross, nestedCrosses, singleCross}
import utest._
object CrossTests extends TestSuite{
  val tests = Tests{
    'singleCross - {
      val check = new TestEvaluator(singleCross)

      val Right(("210", 1)) = check.apply(singleCross.cross("210").suffix)
      val Right(("211", 1)) = check.apply(singleCross.cross("211").suffix)
      val Right(("212", 1)) = check.apply(singleCross.cross("212").suffix)
    }

    'crossResolved - {
      val check = new TestEvaluator(crossResolved)

      val Right(("2.10", 1)) = check.apply(crossResolved.foo("2.10").suffix)
      val Right(("2.11", 1)) = check.apply(crossResolved.foo("2.11").suffix)
      val Right(("2.12", 1)) = check.apply(crossResolved.foo("2.12").suffix)

      val Right(("_2.10", 1)) = check.apply(crossResolved.bar("2.10").longSuffix)
      val Right(("_2.11", 1)) = check.apply(crossResolved.bar("2.11").longSuffix)
      val Right(("_2.12", 1)) = check.apply(crossResolved.bar("2.12").longSuffix)
    }


    'doubleCross - {
      val check = new TestEvaluator(doubleCross)

      val Right(("210_jvm", 1)) = check.apply(doubleCross.cross("210", "jvm").suffix)
      val Right(("210_js", 1)) = check.apply(doubleCross.cross("210", "js").suffix)
      val Right(("211_jvm", 1)) = check.apply(doubleCross.cross("211", "jvm").suffix)
      val Right(("211_js", 1)) = check.apply(doubleCross.cross("211", "js").suffix)
      val Right(("212_jvm", 1)) = check.apply(doubleCross.cross("212", "jvm").suffix)
      val Right(("212_js", 1)) = check.apply(doubleCross.cross("212", "js").suffix)
      val Right(("212_native", 1)) = check.apply(doubleCross.cross("212", "native").suffix)
    }

    'nestedCrosses - {
      val check = new TestEvaluator(nestedCrosses)

      val Right(("210_jvm", 1)) = check.apply(nestedCrosses.cross("210").cross2("jvm").suffix)
      val Right(("210_js", 1)) = check.apply(nestedCrosses.cross("210").cross2("js").suffix)
      val Right(("211_jvm", 1)) = check.apply(nestedCrosses.cross("211").cross2("jvm").suffix)
      val Right(("211_js", 1)) = check.apply(nestedCrosses.cross("211").cross2("js").suffix)
      val Right(("212_jvm", 1)) = check.apply(nestedCrosses.cross("212").cross2("jvm").suffix)
      val Right(("212_js", 1)) = check.apply(nestedCrosses.cross("212").cross2("js").suffix)
      val Right(("212_native", 1)) = check.apply(nestedCrosses.cross("212").cross2("native").suffix)
    }
  }
}
