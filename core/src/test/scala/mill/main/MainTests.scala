package mill.main

import mill.Module
import mill.define.{Segment, Task}
import mill.discover.{Discovered, Mirror}
import mill.util.TestGraphs._
import mill.util.TestUtil.test
import utest._
import Discovered.mapping
object MainTests extends TestSuite{
  def check[T](mapping: Discovered.Mapping[T],
               selectorString: String,
               expected: Either[String, Task[_]]) = {

    val resolved = for{
      args <- mill.main.RunScript.parseArgs(selectorString)
      val crossSelectors = args.map{case Segment.Cross(x) => x.toList.map(_.toString) case _ => Nil}
      task <- mill.main.Resolve.resolve(args, mapping.mirror, mapping.base, Nil, crossSelectors, Nil)
    } yield task
    assert(resolved == expected)
  }
  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    'single - {
      'pos - check(mapping(singleton), "single", Right(singleton.single))
      'neg1 - check(mapping(singleton), "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(mapping(singleton), "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(mapping(singleton), "", Left("Selector cannot be empty"))
    }
    'nested - {

      'pos1 - check(mapping(nestedModule), "single", Right(nestedModule.single))
      'pos2 - check(mapping(nestedModule), "nested.single", Right(nestedModule.nested.single))
      'pos3 - check(mapping(nestedModule), "classInstance.single", Right(nestedModule.classInstance.single))
      'neg1 - check(mapping(nestedModule), "doesntExist", Left("Cannot resolve task doesntExist"))
      'neg2 - check(mapping(nestedModule), "single.doesntExist", Left("Cannot resolve module single"))
      'neg3 - check(mapping(nestedModule), "nested.doesntExist", Left("Cannot resolve task nested.doesntExist"))
      'neg4 - check(mapping(nestedModule), "classInstance.doesntExist", Left("Cannot resolve task classInstance.doesntExist"))
    }
    'cross - {
      'single - {

        'pos1 - check(mapping(singleCross), "cross[210].suffix", Right(singleCross.cross("210").suffix))
        'pos2 - check(mapping(singleCross), "cross[211].suffix", Right(singleCross.cross("211").suffix))
        'neg1 - check(mapping(singleCross), "cross[210].doesntExist", Left("Cannot resolve task cross[210].doesntExist"))
        'neg2 - check(mapping(singleCross), "cross[doesntExist].doesntExist", Left("Cannot resolve cross cross[doesntExist]"))
        'neg2 - check(mapping(singleCross), "cross[doesntExist].suffix", Left("Cannot resolve cross cross[doesntExist]"))
      }
//      'double - {
//
//        'pos1 - check(
//          mapping(doubleCross),
//          "cross[jvm,210].suffix",
//          Right(doubleCross.cross("jvm", "210").suffix)
//        )
//        'pos2 - check(
//          mapping(doubleCross),
//          "cross[jvm,211].suffix",
//          Right(doubleCross.cross("jvm", "211").suffix)
//        )
//      }
      'nested - {
        'indirect - {
          'pos1 - check(
            mapping(indirectNestedCrosses),
            "cross[210].cross2[js].suffix",
            Right(indirectNestedCrosses.cross("210").cross2("js").suffix)
          )
          'pos2 - check(
            mapping(indirectNestedCrosses),
            "cross[211].cross2[jvm].suffix",
            Right(indirectNestedCrosses.cross("211").cross2("jvm").suffix)
          )
        }
        'direct - {
          'pos1 - check(
            mapping(nestedCrosses),
            "cross[210].cross2[js].suffix",
            Right(nestedCrosses.cross("210").cross2("js").suffix)
          )
          'pos2 - check(
            mapping(nestedCrosses),
            "cross[211].cross2[jvm].suffix",
            Right(nestedCrosses.cross("211").cross2("jvm").suffix)
          )
        }
      }
    }

  }
}
