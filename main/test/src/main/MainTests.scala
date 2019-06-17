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
    test("single"){
      val check = MainTests.check(singleton) _
      test("pos") - check("single", Right(Seq(_.single)))
      test("neg1") - check("sngle", Left("Cannot resolve sngle. Did you mean single?"))
      test("neg2") - check("snigle", Left("Cannot resolve snigle. Did you mean single?"))
      test("neg3") - check("nsiigle", Left("Cannot resolve nsiigle. Did you mean single?"))
      test("neg4") - check("ansiigle", Left("Cannot resolve ansiigle. Try `mill resolve _` to see what's available."))
      test("neg5") - check("doesntExist", Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available."))
      test("neg6") - check("single.doesntExist", Left("Task single is not a module and has no children."))
      test("neg7") - check("", Left("Selector cannot be empty"))
    }
    test("backtickIdentifiers"){
      val check = MainTests.check(bactickIdentifiers) _
      test("pos1") - check("up-target", Right(Seq(_.`up-target`)))
      test("pos2") - check("a-down-target", Right(Seq(_.`a-down-target`)))
      test("neg1") - check("uptarget", Left("Cannot resolve uptarget. Did you mean up-target?"))
      test("neg2") - check("upt-arget", Left("Cannot resolve upt-arget. Did you mean up-target?"))
      test("neg3") - check("up-target.doesntExist", Left("Task up-target is not a module and has no children."))
      test("neg4") - check("", Left("Selector cannot be empty"))
      test("neg5") - check("invisible&", Left("Cannot resolve invisible. Try `mill resolve _` to see what's available."))
      test("nested"){
        test("pos") - check("nested-module.nested-target", Right(Seq(_.`nested-module`.`nested-target`)))
        test("neg") - check("nested-module.doesntExist", Left("Cannot resolve nested-module.doesntExist. Try `mill resolve nested-module._` to see what's available."))
      }
    }
    test("nested"){
      val check = MainTests.check(nestedModule) _
      test("pos1") - check("single", Right(Seq(_.single)))
      test("pos2") - check("nested.single", Right(Seq(_.nested.single)))
      test("pos3") - check("classInstance.single", Right(Seq(_.classInstance.single)))
      test("neg1") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "single.doesntExist",
        Left("Task single is not a module and has no children.")
      )
      test("neg3") - check(
        "nested.doesntExist",
        Left("Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available.")
      )
      test("neg3") - check(
        "nested.singel",
        Left("Cannot resolve nested.singel. Did you mean nested.single?")
      )
      test("neg4") - check(
        "classInstance.doesntExist",
        Left("Cannot resolve classInstance.doesntExist. Try `mill resolve classInstance._` to see what's available.")
      )
      test("wildcard") - check(
        "_.single",
        Right(Seq(
          _.classInstance.single,
          _.nested.single
        ))
      )
      test("wildcardNeg") - check(
        "_._.single",
        Left("Cannot resolve _._.single. Try `mill resolve _` to see what's available")
      )
      test("wildcardNeg2") - check(
        "_._.__",
        Left("Cannot resolve _._.__. Try `mill resolve _` to see what's available")
      )
      test("wildcardNeg3") - check(
        "nested._.foobar",
        Left("Cannot resolve nested._.foobar. Try `mill resolve nested._` to see what's available")
      )
      test("wildcard2") - check(
        "__.single",
        Right(Seq(
          _.single,
          _.classInstance.single,
          _.nested.single
        ))
      )

      test("wildcard3") - check(
        "_.__.single",
        Right(Seq(
          _.classInstance.single,
          _.nested.single
        ))
      )

    }
    test("cross"){
      test("single"){
        val check = MainTests.check(singleCross) _
        test("pos1") - check("cross[210].suffix", Right(Seq(_.cross("210").suffix)))
        test("pos2") - check("cross[211].suffix", Right(Seq(_.cross("211").suffix)))
        test("neg1") - check(
          "cross[210].doesntExist",
          Left("Cannot resolve cross[210].doesntExist. Try `mill resolve cross[210]._` to see what's available.")
        )
        test("neg2") - check(
          "cross[doesntExist].doesntExist",
          Left("Cannot resolve cross[doesntExist]. Try `mill resolve cross[__]` to see what's available.")
        )
        test("neg3") - check(
          "cross[221].doesntExist",
          Left("Cannot resolve cross[221]. Did you mean cross[211]?")
        )
        test("neg4") - check(
          "cross[doesntExist].suffix",
          Left("Cannot resolve cross[doesntExist]. Try `mill resolve cross[__]` to see what's available.")
        )
        test("wildcard") - check(
          "cross[_].suffix",
          Right(Seq(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          ))
        )
        test("wildcard2") - check(
          "cross[__].suffix",
          Right(Seq(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          ))
        )
      }
      test("double"){
        val check = MainTests.check(doubleCross) _
        test("pos1") - check(
          "cross[210,jvm].suffix",
          Right(Seq(_.cross("210", "jvm").suffix))
        )
        test("pos2") - check(
          "cross[211,jvm].suffix",
          Right(Seq(_.cross("211", "jvm").suffix))
        )
        test("wildcard"){
          test("labelNeg") - check(
            "_.suffix",
            Left("Cannot resolve _.suffix. Try `mill resolve _._` to see what's available.")
          )
          test("labelPos") - check(
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
          test("first") - check(
            "cross[_,jvm].suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("211", "jvm").suffix,
              _.cross("212", "jvm").suffix
            ))
          )
          test("second") - check(
            "cross[210,_].suffix",
            Right(Seq(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix
            ))
          )
          test("both") - check(
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
          test("both2") - check(
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
      test("nested"){
        val check = MainTests.check(nestedCrosses) _
        test("pos1") - check(
          "cross[210].cross2[js].suffix",
          Right(Seq(_.cross("210").cross2("js").suffix))
        )
        test("pos2") - check(
          "cross[211].cross2[jvm].suffix",
          Right(Seq(_.cross("211").cross2("jvm").suffix))
        )
        test("wildcard"){
          test("first") - check(
            "cross[_].cross2[jvm].suffix",
            Right(Seq(
              _.cross("210").cross2("jvm").suffix,
              _.cross("211").cross2("jvm").suffix,
              _.cross("212").cross2("jvm").suffix
            ))
          )
          test("second") - check(
            "cross[210].cross2[_].suffix",
            Right(Seq(
              _.cross("210").cross2("jvm").suffix,
              _.cross("210").cross2("js").suffix,
              _.cross("210").cross2("native").suffix
            ))
          )
          test("both") - check(
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
