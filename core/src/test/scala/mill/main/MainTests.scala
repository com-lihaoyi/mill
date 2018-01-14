package mill.main

import mill.Module
import mill.define.{Discover, Segment, Task}
import mill.util.TestGraphs._
import mill.util.TestUtil.test
import utest._
object MainTests extends TestSuite{
  def check[T](module: mill.Module,
               discover: Discover,
               selectorString: String,
               expected: Either[String, Task[_]]) = {

    val resolved = for{
      selectors <- mill.main.ParseArgs(Seq(selectorString)).map(_._1.head)
      val crossSelectors = selectors.map{case Segment.Cross(x) => x.toList.map(_.toString) case _ => Nil}
      task <- mill.main.Resolve.resolve(selectors, module, discover, Nil, crossSelectors, Nil)
    } yield task
    assert(resolved == expected)
  }
  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    'single - {
      'pos - check(singleton, Discover[singleton.type], "single", Right(singleton.single))
      'neg1 - check(singleton, Discover[singleton.type], "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(singleton, Discover[singleton.type], "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(singleton, Discover[singleton.type], "", Left("Selector cannot be empty"))
    }
    'nested - {

      'pos1 - check(nestedModule, Discover[nestedModule.type], "single", Right(nestedModule.single))
      'pos2 - check(nestedModule, Discover[nestedModule.type], "nested.single", Right(nestedModule.nested.single))
      'pos3 - check(nestedModule, Discover[nestedModule.type], "classInstance.single", Right(nestedModule.classInstance.single))
      'neg1 - check(nestedModule, Discover[nestedModule.type], "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(nestedModule, Discover[nestedModule.type], "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(nestedModule, Discover[nestedModule.type], "nested.doesntExist", Left("Cannot resolve task nested.doesntExist"))
      'neg4 - check(nestedModule, Discover[nestedModule.type], "classInstance.doesntExist", Left("Cannot resolve task classInstance.doesntExist"))
    }
    'cross - {
      'single - {

        'pos1 - check(singleCross, Discover[singleCross.type], "cross[210].suffix", Right(singleCross.cross("210").suffix))
        'pos2 - check(singleCross, Discover[singleCross.type], "cross[211].suffix", Right(singleCross.cross("211").suffix))
        'neg1 - check(singleCross, Discover[singleCross.type], "cross[210].doesntExist", Left("Cannot resolve task cross[210].doesntExist"))
        'neg2 - check(singleCross, Discover[singleCross.type], "cross[doesntExist].doesntExist", Left("Cannot resolve cross cross[doesntExist]"))
        'neg2 - check(singleCross, Discover[singleCross.type], "cross[doesntExist].suffix", Left("Cannot resolve cross cross[doesntExist]"))
      }
//      'double - {
//
//        'pos1 - check(
//          doubleCross,
//          "cross[jvm,210].suffix",
//          Right(doubleCross.cross("jvm", "210").suffix)
//        )
//        'pos2 - check(
//          doubleCross,
//          "cross[jvm,211].suffix",
//          Right(doubleCross.cross("jvm", "211").suffix)
//        )
//      }
      'nested - {
        'indirect - {
          'pos1 - check(
            indirectNestedCrosses,
            Discover[indirectNestedCrosses.type],
            "cross[210].cross2[js].suffix",
            Right(indirectNestedCrosses.cross("210").cross2("js").suffix)
          )
          'pos2 - check(
            indirectNestedCrosses,
            Discover[indirectNestedCrosses.type],
            "cross[211].cross2[jvm].suffix",
            Right(indirectNestedCrosses.cross("211").cross2("jvm").suffix)
          )
        }
        'direct - {
          'pos1 - check(
            nestedCrosses,
            Discover[nestedCrosses.type],
            "cross[210].cross2[js].suffix",
            Right(nestedCrosses.cross("210").cross2("js").suffix)
          )
          'pos2 - check(
            nestedCrosses,
            Discover[nestedCrosses.type],
            "cross[211].cross2[jvm].suffix",
            Right(nestedCrosses.cross("211").cross2("jvm").suffix)
          )
        }
      }
    }

  }
}
