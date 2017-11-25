package mill.main

import mill.Module
import mill.define.Task
import mill.discover.{Discovered, Mirror}
import mill.util.TestGraphs._
import mill.util.TestUtil.test
import utest._

object MainTests extends TestSuite{
  def check[T: Discovered](obj: T,
                           selectorString: String,
                           expected: Either[String, Task[_]]) = {
    val mirror = implicitly[Discovered[T]].mirror
    val resolved = for{
      args <- mill.Main.parseArgs(selectorString)
      val crossSelectors = args.map{case Mirror.Segment.Cross(x) => x.toList.map(_.toString) case _ => Nil}
      task <- mill.Main.resolve(args, mirror, obj, Nil, crossSelectors, Nil)
    } yield task
    assert(resolved == expected)
  }
  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    'single - {
      'pos - check(singleton, "single", Right(singleton.single))
      'neg1 - check(singleton, "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(singleton, "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(singleton, "", Left("Selector cannot be empty"))
    }
    'nested - {

      'pos1 - check(nestedModule, "single", Right(nestedModule.single))
      'pos2 - check(nestedModule, "nested.single", Right(nestedModule.nested.single))
      'pos3 - check(nestedModule, "classInstance.single", Right(nestedModule.classInstance.single))
      'neg1 - check(nestedModule, "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(nestedModule, "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(nestedModule, "nested.doesntExist", Left("Cannot resolve task nested.doesntExist"))
      'neg4 - check(nestedModule, "classInstance.doesntExist", Left("Cannot resolve task classInstance.doesntExist"))
    }
    'cross - {
      'single - {

        'pos1 - check(singleCross, "cross[210].suffix", Right(singleCross.cross("210").suffix))
        'pos2 - check(singleCross, "cross[211].suffix", Right(singleCross.cross("211").suffix))
        'neg1 - check(singleCross, "cross[210].doesntExist", Left("Cannot resolve task cross[210].doesntExist"))
  //      'neg2 - check(outer, "cross[doesntExist].doesntExist", Left("Cannot resolve cross cross[doesntExist]"))
  //      'neg2 - check(outer, "cross[doesntExist].target", Left("Cannot resolve cross cross[doesntExist]"))
      }
      'double - {

        'pos1 - check(
          doubleCross,
          "cross[jvm,210].suffix",
          Right(doubleCross.cross("jvm", "210").suffix)
        )
        'pos2 - check(
          doubleCross,
          "cross[jvm,211].suffix",
          Right(doubleCross.cross("jvm", "211").suffix)
        )
      }
      'nested - {
        'indirect - {
          'pos1 - check(
            indirectNestedCrosses,
            "cross[210].cross2[js].suffix",
            Right(indirectNestedCrosses.cross("210").cross2("js").suffix)
          )
          'pos2 - check(
            indirectNestedCrosses,
            "cross[211].cross2[jvm].suffix",
            Right(indirectNestedCrosses.cross("211").cross2("jvm").suffix)
          )
        }
        'direct - {
          'pos1 - check(
            nestedCrosses,
            "cross[210].cross2[js].suffix",
            Right(nestedCrosses.cross("210").cross2("js").suffix)
          )
          'pos2 - check(
            nestedCrosses,
            "cross[211].cross2[jvm].suffix",
            Right(nestedCrosses.cross("211").cross2("jvm").suffix)
          )
        }
      }
    }

  }
}
