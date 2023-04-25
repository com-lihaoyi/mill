package mill.main

import mill.define.{NamedTask, SelectMode}
import mill.util.TestGraphs._
import utest._
object ResolversTests extends TestSuite {

  def check[T <: mill.define.BaseModule](module: T)(
      selectorString: String,
      expected0: Either[String, Set[T => NamedTask[_]]]
  ) = checkSeq(module)(Seq(selectorString), expected0)

  def checkSeq[T <: mill.define.BaseModule](module: T)(
      selectorStrings: Seq[String],
      expected0: Either[String, Set[T => NamedTask[_]]]
  ) = {

    val expected = expected0.map(_.map(_(module)))
    val resolved = for {
      task <- mill.main.ResolveTasks.resolveTasks0(
        None,
        module,
        selectorStrings,
        SelectMode.Separated
      )
    } yield task

    assert(resolved.map(_.map(_.toString).toSet[String]) == expected.map(_.map(_.toString)))
  }

  val tests = Tests {
    val graphs = new mill.util.TestGraphs()
    import graphs._
    "single" - {
      val check = ResolversTests.check(singleton) _
      "pos" - check("single", Right(Set(_.single)))
      "posCurly" - check("{single}", Right(Set(_.single)))
      "neg1" - check("sngle", Left("Cannot resolve sngle. Did you mean single?"))
      "neg2" - check("snigle", Left("Cannot resolve snigle. Did you mean single?"))
      "neg3" - check("nsiigle", Left("Cannot resolve nsiigle. Did you mean single?"))
      "neg4" - check(
        "ansiigle",
        Left("Cannot resolve ansiigle. Try `mill resolve _` to see what's available.")
      )
      "neg5" - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      "neg6" - check(
        "single.doesntExist",
        Left("Cannot resolve single.doesntExist. single resolves to a Task with no children.")
      )
      "neg7" - check("", Left("Selector cannot be empty"))
    }
    "backtickIdentifiers" - {
      val check = ResolversTests.check(bactickIdentifiers) _
      "pos1" - check("up-target", Right(Set(_.`up-target`)))
      "pos2" - check("a-down-target", Right(Set(_.`a-down-target`)))
      "neg1" - check("uptarget", Left("Cannot resolve uptarget. Did you mean up-target?"))
      "neg2" - check("upt-arget", Left("Cannot resolve upt-arget. Did you mean up-target?"))
      "neg3" - check(
        "up-target.doesntExist",
        Left("Cannot resolve up-target.doesntExist. up-target resolves to a Task with no children.")
      )
      "neg4" - check("", Left("Selector cannot be empty"))
      "neg5" - check(
        "invisible&",
        Left("Cannot resolve invisible. Try `mill resolve _` to see what's available.")
      )
      "nested" - {
        "pos" - check("nested-module.nested-target", Right(Set(_.`nested-module`.`nested-target`)))
        "neg" - check(
          "nested-module.doesntExist",
          Left(
            "Cannot resolve nested-module.doesntExist. Try `mill resolve nested-module._` to see what's available."
          )
        )
      }
    }
    "nested" - {
      val check = ResolversTests.check(nestedModule) _
      "pos1" - check("single", Right(Set(_.single)))
      "pos2" - check("nested.single", Right(Set(_.nested.single)))
      "pos3" - check("classInstance.single", Right(Set(_.classInstance.single)))
      "posCurly1" - check("classInstance.{single}", Right(Set(_.classInstance.single)))
      "posCurly2" - check(
        "{nested,classInstance}.single",
        Right(Set(_.nested.single, _.classInstance.single))
      )
      "posCurly3" - check(
        "{nested,classInstance}.{single}",
        Right(Set(_.nested.single, _.classInstance.single))
      )
      "posCurly4" - check(
        "{single,{nested,classInstance}.{single}}",
        Right(Set(_.single, _.nested.single, _.classInstance.single))
      )
      "posCurly5" - check(
        "{single,nested.single,classInstance.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single))
      )
      "posCurly6" - check(
        "{{single},{nested,classInstance}.{single}}",
        Right(Set(_.single, _.nested.single, _.classInstance.single))
      )
      "neg1" - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      "neg2" - check(
        "single.doesntExist",
        Left("Cannot resolve single.doesntExist. single resolves to a Task with no children.")
      )
      "neg3" - check(
        "nested.doesntExist",
        Left(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      "neg3" - check(
        "nested.singel",
        Left("Cannot resolve nested.singel. Did you mean nested.single?")
      )
      "neg4" - check(
        "classInstance.doesntExist",
        Left(
          "Cannot resolve classInstance.doesntExist. Try `mill resolve classInstance._` to see what's available."
        )
      )
      "wildcard" - check(
        "_.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        ))
      )
      "wildcardNeg" - check(
        "_._.single",
        Left("Cannot resolve _._.single. Try `mill resolve _` to see what's available.")
      )
      "wildcardNeg2" - check(
        "_._.__",
        Left("Cannot resolve _._.__. Try `mill resolve _` to see what's available.")
      )
      "wildcardNeg3" - check(
        "nested._.foobar",
        Left("Cannot resolve nested._.foobar. nested._ resolves to a Task with no children.")
      )
      "wildcard2" - check(
        "__.single",
        Right(Set(
          _.single,
          _.classInstance.single,
          _.nested.single
        ))
      )

      "wildcard3" - check(
        "_.__.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        ))
      )
    }
    "doubleNested" - {
      val check = ResolversTests.check(doubleNestedModule) _
      "pos1" - check("single", Right(Set(_.single)))
      "pos2" - check("nested.single", Right(Set(_.nested.single)))
      "pos3" - check("nested.inner.single", Right(Set(_.nested.inner.single)))
      "neg1" - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      "neg2" - check(
        "nested.doesntExist",
        Left(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      "neg3" - check(
        "nested.inner.doesntExist",
        Left(
          "Cannot resolve nested.inner.doesntExist. Try `mill resolve nested.inner._` to see what's available."
        )
      )
      "neg4" - check(
        "nested.inner.doesntExist.alsoDoesntExist2",
        Left(
          "Cannot resolve nested.inner.doesntExist.alsoDoesntExist2. Try `mill resolve nested.inner._` to see what's available."
        )
      )
    }

    "cross" - {
      "single" - {
        val check = ResolversTests.check(singleCross) _
        "pos1" - check("cross[210].suffix", Right(Set(_.cross("210").suffix)))
        "pos2" - check("cross[211].suffix", Right(Set(_.cross("211").suffix)))
        "posCurly" - check(
          "cross[{210,211}].suffix",
          Right(Set(_.cross("210").suffix, _.cross("211").suffix))
        )
        "neg1" - check(
          "cross[210].doesntExist",
          Left(
            "Cannot resolve cross[210].doesntExist. Try `mill resolve cross[210]._` to see what's available."
          )
        )
        "neg2" - check(
          "cross[doesntExist].doesntExist",
          Left(
            "Cannot resolve cross[doesntExist].doesntExist. Try `mill resolve cross._` to see what's available."
          )
        )
        "neg3" - check(
          "cross[221].doesntExist",
          Left("Cannot resolve cross[221].doesntExist. Did you mean cross[211]?")
        )
        "neg4" - check(
          "cross[doesntExist].suffix",
          Left(
            "Cannot resolve cross[doesntExist].suffix. Try `mill resolve cross._` to see what's available."
          )
        )
        "wildcard" - check(
          "cross[_].suffix",
          Right(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          ))
        )
        "wildcard2" - check(
          "cross[__].suffix",
          Right(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          ))
        )
      }
      "double" - {
        val check = ResolversTests.check(doubleCross) _
        "pos1" - check(
          "cross[210,jvm].suffix",
          Right(Set(_.cross("210", "jvm").suffix))
        )
        "pos2" - check(
          "cross[211,jvm].suffix",
          Right(Set(_.cross("211", "jvm").suffix))
        )
        "wildcard" - {
          "labelNeg1" - check(
            "_.suffix",
            Left("Cannot resolve _.suffix. Try `mill resolve _._` to see what's available.")
          )
          "labelNeg2" - check(
            "_.doesntExist",
            Left(
              "Cannot resolve _.doesntExist. Try `mill resolve _._` to see what's available."
            )
          )
          "labelNeg3" - check(
            "__.doesntExist",
            Left("Cannot resolve __.doesntExist. Try `mill resolve _` to see what's available.")
          )
          "labelNeg4" - check(
            "cross.__.doesntExist",
            Left(
              "Cannot resolve cross.__.doesntExist. Try `mill resolve cross._` to see what's available."
            )
          )
          "labelNeg5" - check(
            "cross._.doesntExist",
            Left(
              "Cannot resolve cross._.doesntExist. Try `mill resolve cross._` to see what's available."
            )
          )
          "labelPos" - check(
            "__.suffix",
            Right(Set(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix,
              _.cross("211", "jvm").suffix,
              _.cross("211", "js").suffix,
              _.cross("212", "jvm").suffix,
              _.cross("212", "js").suffix,
              _.cross("212", "native").suffix
            ))
          )
          "first" - check(
            "cross[_,jvm].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix,
              _.cross("211", "jvm").suffix,
              _.cross("212", "jvm").suffix
            ))
          )
          "second" - check(
            "cross[210,_].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix
            ))
          )
          "both" - check(
            "cross[_,_].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix,
              _.cross("211", "jvm").suffix,
              _.cross("211", "js").suffix,
              _.cross("212", "jvm").suffix,
              _.cross("212", "js").suffix,
              _.cross("212", "native").suffix
            ))
          )
          "both2" - check(
            "cross[__].suffix",
            Right(Set(
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
      "nested" - {
        val check = ResolversTests.check(nestedCrosses) _
        "pos1" - check(
          "cross[210].cross2[js].suffix",
          Right(Set(_.cross("210").cross2("js").suffix))
        )
        "pos2" - check(
          "cross[211].cross2[jvm].suffix",
          Right(Set(_.cross("211").cross2("jvm").suffix))
        )
        "pos2NoDefaultTask" - check(
          "cross[211].cross2[jvm]",
          Left("Cannot find default task to evaluate for module cross[211].cross2[jvm]")
        )
        "wildcard" - {
          "first" - check(
            "cross[_].cross2[jvm].suffix",
            Right(Set(
              _.cross("210").cross2("jvm").suffix,
              _.cross("211").cross2("jvm").suffix,
              _.cross("212").cross2("jvm").suffix
            ))
          )
          "second" - check(
            "cross[210].cross2[_].suffix",
            Right(Set(
              _.cross("210").cross2("jvm").suffix,
              _.cross("210").cross2("js").suffix,
              _.cross("210").cross2("native").suffix
            ))
          )
          "both" - check(
            "cross[_].cross2[_].suffix",
            Right(Set(
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

      "nestedCrossTaskModule" - {
        val check = ResolversTests.checkSeq(nestedTaskCrosses) _
        "pos1" - check(
          Seq("cross1[210].cross2[js].suffixCmd"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd()))
        )
        "pos1Default" - check(
          Seq("cross1[210].cross2[js]"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd()))
        )
        "pos1WithWildcard" - check(
          Seq("cross1[210].cross2[js]._"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd()))
        )
        "pos1WithArgs" - check(
          Seq("cross1[210].cross2[js].suffixCmd", "suffix-arg"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd("suffix-arg")))
        )
        "pos2" - check(
          Seq("cross1[211].cross2[jvm].suffixCmd"),
          Right(Set(_.cross1("211").cross2("jvm").suffixCmd()))
        )
        "pos2Default" - check(
          Seq("cross1[211].cross2[jvm]"),
          Right(Set(_.cross1("211").cross2("jvm").suffixCmd()))
        )
      }
    }
  }
}
