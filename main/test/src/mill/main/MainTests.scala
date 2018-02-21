package mill.main

import mill.define.{Discover, Segment, Task}
import mill.util.TestGraphs._

import utest._
object MainTests extends TestSuite{

  def check[T <: mill.define.BaseModule](module: T)(
                                         selectorString: String,
                                         expected0: Either[String, Seq[T => Task[_]]])= {

    val expected = expected0.map(_.map(_(module)))
    val resolved = for{
      selectors <- mill.util.ParseArgs(Seq(selectorString), multiSelect = false).map(_._1.head)
      val crossSelectors = selectors._2.value.map{case Segment.Cross(x) => x.toList.map(_.toString) case _ => Nil}
      task <- mill.main.ResolveTasks.resolve(
        selectors._2.value.toList, module, module.millDiscover, Nil, crossSelectors.toList, Nil
      )
    } yield task
    assert(resolved == expected)
  }
  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    'single - {
      val check = MainTests.check(singleton) _
      'pos - check("single", Right(Seq(_.single)))
      'neg1 - check("sngle", Left("Cannot resolve sngle. Did you mean single?"))
      'neg2 - check("snigle", Left("Cannot resolve snigle. Did you mean single?"))
      'neg3 - check("nsiigle", Left("Cannot resolve nsiigle. Did you mean single?"))
      'neg4 - check("ansiigle", Left("Cannot resolve ansiigle. Try `mill resolve _` to see what's available."))
      'neg5 - check("doesntExist", Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available."))
      'neg6 - check("single.doesntExist", Left("Task single is not a module and has no children."))
      'neg7 - check("", Left("Selector cannot be empty"))
    }
    'nested - {
      val check = MainTests.check(nestedModule) _
      'pos1 - check("single", Right(Seq(_.single)))
      'pos2 - check("nested.single", Right(Seq(_.nested.single)))
      'pos3 - check("classInstance.single", Right(Seq(_.classInstance.single)))
      'neg1 - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      'neg2 - check(
        "single.doesntExist",
        Left("Task single is not a module and has no children.")
      )
      'neg3 - check(
        "nested.doesntExist",
        Left("Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available.")
      )
      'neg3 - check(
        "nested.singel",
        Left("Cannot resolve nested.singel. Did you mean nested.single?")
      )
      'neg4 - check(
        "classInstance.doesntExist",
        Left("Cannot resolve classInstance.doesntExist. Try `mill resolve classInstance._` to see what's available.")
      )
      'wildcard - check(
        "_.single",
        Right(Seq(
          _.classInstance.single,
          _.nested.single
        ))
      )
      'wildcardNeg - check(
        "_._.single",
        Left("Cannot resolve _._.single. Try `mill resolve _` to see what's available")
      )
      'wildcardNeg2 - check(
        "_._.__",
        Left("Cannot resolve _._.__. Try `mill resolve _` to see what's available")
      )
      'wildcardNeg3 - check(
        "nested._.foobar",
        Left("Cannot resolve nested._.foobar. Try `mill resolve nested._` to see what's available")
      )
      'wildcard2 - check(
        "__.single",
        Right(Seq(
          _.single,
          _.classInstance.single,
          _.nested.single
        ))
      )

      'wildcard3 - check(
        "_.__.single",
        Right(Seq(
          _.classInstance.single,
          _.nested.single
        ))
      )

    }
    'cross - {
      'single - {
        val check = MainTests.check(singleCross) _
        'pos1 - check("cross[210].suffix", Right(Seq(_.cross("210").suffix)))
        'pos2 - check("cross[211].suffix", Right(Seq(_.cross("211").suffix)))
        'neg1 - check(
          "cross[210].doesntExist",
          Left("Cannot resolve cross[210].doesntExist. Try `mill resolve cross[210]._` to see what's available.")
        )
        'neg2 - check(
          "cross[doesntExist].doesntExist",
          Left("Cannot resolve cross[doesntExist]. Try `mill resolve cross[__]` to see what's available.")
        )
        'neg3 - check(
          "cross[221].doesntExist",
          Left("Cannot resolve cross[221]. Did you mean cross[211]?")
        )
        'neg4 - check(
          "cross[doesntExist].suffix",
          Left("Cannot resolve cross[doesntExist]. Try `mill resolve cross[__]` to see what's available.")
        )
        'wildcard - check(
          "cross[_].suffix",
          Right(Seq(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          ))
        )
        'wildcard2 - check(
          "cross[__].suffix",
          Right(Seq(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          ))
        )
      }
      'double - {
        val check = MainTests.check(doubleCross) _
        'pos1 - check(
          "cross[210,jvm].suffix",
          Right(Seq(_.cross("210", "jvm").suffix))
        )
        'pos2 - check(
          "cross[211,jvm].suffix",
          Right(Seq(_.cross("211", "jvm").suffix))
        )
        'wildcard - {
          'labelNeg - check(
            "_.suffix",
            Left("Cannot resolve _.suffix. Try `mill resolve _._` to see what's available.")
          )
          'labelPos - check(
            "__.suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix,

              _.cross("211", "jvm").suffix,
              _.cross("211", "js").suffix,

              _.cross("212", "jvm").suffix,
              _.cross("212", "js").suffix,
              _.cross("212", "native").suffix
            ))
          )
          'first - check(
            "cross[_,jvm].suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("211", "jvm").suffix,
              _.cross("212", "jvm").suffix
            ))
          )
          'second - check(
            "cross[210,_].suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix
            ))
          )
          'both - check(
            "cross[_,_].suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix,

              _.cross("211", "jvm").suffix,
              _.cross("211", "js").suffix,

              _.cross("212", "jvm").suffix,
              _.cross("212", "js").suffix,
              _.cross("212", "native").suffix
            ))
          )
          'both2 - check(
            "cross[__].suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix,

              _.cross("211", "jvm").suffix,
              _.cross("211", "js").suffix,

              _.cross("212", "jvm").suffix,
              _.cross("212", "js").suffix,
              _.cross("212", "native").suffix
            ))
          )
        }
      }
      'nested - {
        val check = MainTests.check(nestedCrosses) _
        'pos1 - check(
          "cross[210].cross2[js].suffix",
          Right(Seq(_.cross("210").cross2("js").suffix))
        )
        'pos2 - check(
          "cross[211].cross2[jvm].suffix",
          Right(Seq(_.cross("211").cross2("jvm").suffix))
        )
        'wildcard - {
          'first - check(
            "cross[_].cross2[jvm].suffix",
            Right(Seq(
              _.cross("210").cross2("jvm").suffix,
              _.cross("211").cross2("jvm").suffix,
              _.cross("212").cross2("jvm").suffix
            ))
          )
          'second - check(
            "cross[210].cross2[_].suffix",
            Right(Seq(
              _.cross("210").cross2("jvm").suffix,
              _.cross("210").cross2("js").suffix,
              _.cross("210").cross2("native").suffix
            ))
          )
          'both - check(
            "cross[_].cross2[_].suffix",
            Right(Seq(
              _.cross("210").cross2("jvm").suffix,
              _.cross("210").cross2("js").suffix,
              _.cross("210").cross2("native").suffix,

              _.cross("211").cross2("jvm").suffix,
              _.cross("211").cross2("js").suffix,
              _.cross("211").cross2("native").suffix,

              _.cross("212").cross2("jvm").suffix,
              _.cross("212").cross2("js").suffix,
              _.cross("212").cross2("native").suffix
            ))
          )
        }
      }
    }
  }
}
