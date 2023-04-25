package mill.main

import mill.define.{NamedTask, SelectMode}
import mill.util.TestGraphs._
import utest._
object ResolversTests extends TestSuite {

  class Checker[T <: mill.define.BaseModule](module: T) {

    def apply(
        selectorString: String,
        expected0: Either[String, Set[T => NamedTask[_]]],
        expectedMetadata: Set[String] = Set()
    ) = checkSeq(Seq(selectorString), expected0, expectedMetadata)

    def checkSeq(
        selectorStrings: Seq[String],
        expected0: Either[String, Set[T => NamedTask[_]]],
        expectedMetadata: Set[String] = Set()
    ) = {
      val expected = expected0.map(_.map(_(module)))
      val (resolvedTasks, resolvedMetadata) = resolveTasksAndMetadata(selectorStrings)
      assert(
        resolvedTasks.map(_.map(_.toString).toSet[String]) ==
          expected.map(_.map(_.toString))
      )
      assert(
        expectedMetadata.isEmpty ||
          resolvedMetadata.map(_.toSet) == Right(expectedMetadata)
      )
    }

    def checkSeq0(
        selectorStrings: Seq[String],
        check: Either[String, List[NamedTask[_]]] => Boolean,
        checkMetadata: Either[String, List[String]] => Boolean = _ => true
    ) = {

      val (resolvedTasks, resolvedMetadata) = resolveTasksAndMetadata(selectorStrings)
      assert(check(resolvedTasks))
      assert(checkMetadata(resolvedMetadata))
    }

    def resolveTasksAndMetadata(selectorStrings: Seq[String]) = {
      val resolvedTasks = mill.main.ResolveTasks.resolve0(
        None,
        module,
        selectorStrings,
        SelectMode.Separated
      )

      val resolvedMetadata = mill.main.ResolveMetadata.resolve0(
        None,
        module,
        selectorStrings,
        SelectMode.Separated
      )

      (resolvedTasks, resolvedMetadata)
    }
  }

  val tests = Tests {
    val graphs = new mill.util.TestGraphs()
    import graphs._
    "single" - {
      val check = new Checker(singleton)
      "pos" - check("single", Right(Set(_.single)), Set("single"))
      "wildcard" - check("_", Right(Set(_.single)), Set("single"))
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
      val check = new Checker(bactickIdentifiers)
      "pos1" - check("up-target", Right(Set(_.`up-target`)), Set("up-target"))
      "pos2" - check("a-down-target", Right(Set(_.`a-down-target`)), Set("a-down-target"))
      "neg1" - check("uptarget", Left("Cannot resolve uptarget. Did you mean up-target?"))
      "neg2" - check("upt-arget", Left("Cannot resolve upt-arget. Did you mean up-target?"))
      "neg3" - check(
        "up-target.doesntExist",
        Left("Cannot resolve up-target.doesntExist. up-target resolves to a Task with no children.")
      )
      "neg4" - check("", Left("Selector cannot be empty"))
      "neg5" - check(
        "invisible",
        Left("Cannot resolve invisible. Try `mill resolve _` to see what's available.")
      )
      "negBadParse" - check(
        "invisible&",
        Left("Parsing exception Position 1:10, found \"&\"")
      )
      "nested" - {
        "pos" - check(
          "nested-module.nested-target",
          Right(Set(_.`nested-module`.`nested-target`)),
          Set("nested-module.nested-target")
        )
        "neg" - check(
          "nested-module.doesntExist",
          Left(
            "Cannot resolve nested-module.doesntExist. Try `mill resolve nested-module._` to see what's available."
          )
        )
      }
    }
    "nested" - {
      val check = new Checker(nestedModule)
      "pos1" - check("single", Right(Set(_.single)), Set("single"))
      "pos2" - check("nested.single", Right(Set(_.nested.single)), Set("nested.single"))
      "pos3" - check(
        "classInstance.single",
        Right(Set(_.classInstance.single)),
        Set("classInstance.single")
      )
      "posCurly1" - check(
        "{nested,classInstance}.single",
        Right(Set(_.nested.single, _.classInstance.single)),
        Set("nested.single", "classInstance.single")
      )
      "posCurly2" - check(
        "{single,{nested,classInstance}.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      "posCurly3" - check(
        "{single,nested.single,classInstance.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      "posCurly4" - check(
        "{,nested.,classInstance.}single",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
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
        )),
        Set("nested.single", "classInstance.single")
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
        )),
        Set("single", "nested.single", "classInstance.single")
      )

      "wildcard3" - check(
        "_.__.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
    }
    "doubleNested" - {
      val check = new Checker(doubleNestedModule)
      "pos1" - check("single", Right(Set(_.single)), Set("single"))
      "pos2" - check("nested.single", Right(Set(_.nested.single)), Set("nested.single"))
      "pos3" - check(
        "nested.inner.single",
        Right(Set(_.nested.inner.single)),
        Set("nested.inner.single")
      )
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
        val check = new Checker(singleCross)
        "pos1" - check(
          "cross[210].suffix",
          Right(Set(_.cross("210").suffix)),
          Set("cross[210].suffix")
        )
        "pos2" - check(
          "cross[211].suffix",
          Right(Set(_.cross("211").suffix)),
          Set("cross[211].suffix")
        )
        "posCurly" - check(
          "cross[{210,211}].suffix",
          Right(Set(_.cross("210").suffix, _.cross("211").suffix)),
          Set("cross[210].suffix", "cross[211].suffix")
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
          )),
          Set("cross[210].suffix", "cross[211].suffix", "cross[212].suffix")
        )
        "wildcard2" - check(
          "cross[__].suffix",
          Right(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          )),
          Set("cross[210].suffix", "cross[211].suffix", "cross[212].suffix")
        )
      }
      "double" - {
        val check = new Checker(doubleCross)
        "pos1" - check(
          "cross[210,jvm].suffix",
          Right(Set(_.cross("210", "jvm").suffix)),
          Set("cross[210,jvm].suffix")
        )
        "pos2" - check(
          "cross[211,jvm].suffix",
          Right(Set(_.cross("211", "jvm").suffix)),
          Set("cross[211,jvm].suffix")
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
            )),
            Set(
              "cross[210,jvm].suffix",
              "cross[210,js].suffix",
              "cross[211,jvm].suffix",
              "cross[211,js].suffix",
              "cross[212,jvm].suffix",
              "cross[212,js].suffix",
              "cross[212,native].suffix"
            )
          )
          "first" - check(
            "cross[_,jvm].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix,
              _.cross("211", "jvm").suffix,
              _.cross("212", "jvm").suffix
            )),
            Set(
              "cross[210,jvm].suffix",
              "cross[211,jvm].suffix",
              "cross[212,jvm].suffix"
            )
          )
          "second" - check(
            "cross[210,_].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix,
              _.cross("210", "js").suffix
            )),
            Set(
              "cross[210,jvm].suffix",
              "cross[210,js].suffix"
            )
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
            )),
            Set(
              "cross[210,jvm].suffix",
              "cross[210,js].suffix",
              "cross[211,jvm].suffix",
              "cross[211,js].suffix",
              "cross[212,jvm].suffix",
              "cross[212,js].suffix",
              "cross[212,native].suffix"
            )
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
            )),
            Set(
              "cross[210,jvm].suffix",
              "cross[210,js].suffix",
              "cross[211,jvm].suffix",
              "cross[211,js].suffix",
              "cross[212,jvm].suffix",
              "cross[212,js].suffix",
              "cross[212,js].suffix",
              "cross[212,native].suffix"
            )
          )
        }
      }
      "nested" - {
        val check = new Checker(nestedCrosses)
        "pos1" - check(
          "cross[210].cross2[js].suffix",
          Right(Set(_.cross("210").cross2("js").suffix)),
          Set("cross[210].cross2[js].suffix")
        )
        "pos2" - check(
          "cross[211].cross2[jvm].suffix",
          Right(Set(_.cross("211").cross2("jvm").suffix)),
          Set("cross[211].cross2[jvm].suffix")
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
            )),
            Set(
              "cross[210].cross2[jvm].suffix",
              "cross[211].cross2[jvm].suffix",
              "cross[212].cross2[jvm].suffix"
            )
          )
          "second" - check(
            "cross[210].cross2[_].suffix",
            Right(Set(
              _.cross("210").cross2("jvm").suffix,
              _.cross("210").cross2("js").suffix,
              _.cross("210").cross2("native").suffix
            )),
            Set(
              "cross[210].cross2[jvm].suffix",
              "cross[210].cross2[js].suffix",
              "cross[210].cross2[native].suffix"
            )
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
            )),
            Set(
              "cross[210].cross2[jvm].suffix",
              "cross[210].cross2[js].suffix",
              "cross[210].cross2[native].suffix",
              "cross[211].cross2[jvm].suffix",
              "cross[211].cross2[js].suffix",
              "cross[211].cross2[native].suffix",
              "cross[212].cross2[jvm].suffix",
              "cross[212].cross2[js].suffix",
              "cross[212].cross2[native].suffix"
            )
          )
        }
      }

      "nestedCrossTaskModule" - {
        val check = new Checker(nestedTaskCrosses)
        "pos1" - check.checkSeq(
          Seq("cross1[210].cross2[js].suffixCmd"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        "pos1Default" - check.checkSeq(
          Seq("cross1[210].cross2[js]"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js]")
        )
        "pos1WithWildcard" - check.checkSeq(
          Seq("cross1[210].cross2[js]._"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        "pos1WithArgs" - check.checkSeq(
          Seq("cross1[210].cross2[js].suffixCmd", "suffix-arg"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd("suffix-arg"))),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        "pos2" - check.checkSeq(
          Seq("cross1[211].cross2[jvm].suffixCmd"),
          Right(Set(_.cross1("211").cross2("jvm").suffixCmd())),
          Set("cross1[211].cross2[jvm].suffixCmd")
        )
        "pos2Default" - check.checkSeq(
          Seq("cross1[211].cross2[jvm]"),
          Right(Set(_.cross1("211").cross2("jvm").suffixCmd())),
          Set("cross1[211].cross2[jvm]")
        )
      }
    }

    "moduleInitError" - {
      "simple" - {
        val check = new Checker(moduleInitError)
        // We can resolve the root module tasks even when the
        // sub-modules fail to initialize
        "rootTarget" - check.checkSeq(
          Seq("rootTarget"),
          Right(Set(_.rootTarget))
        )
        "rootCommand" - check.checkSeq(
          Seq("rootCommand", "hello"),
          Right(Set(_.rootCommand("hello")))
        )

        // Resolving tasks on a module that fails to initialize is properly
        // caught and reported in the Either result
        "fooTarget" - check.checkSeq0(
          Seq("foo.fooTarget"),
          res => res.isLeft && res.left.exists(_.contains("Foo Boom"))
        )
        "fooCommand" - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          res => res.isLeft && res.left.exists(_.contains("Foo Boom"))
        )

        // Sub-modules that can initialize allow tasks to be resolved, even
        // if their siblings or children are broken
        "barTarget" - check.checkSeq(
          Seq("bar.barTarget"),
          Right(Set(_.bar.barTarget))
        )
        "barCommand" - check.checkSeq(
          Seq("bar.barCommand", "hello"),
          Right(Set(_.bar.barCommand("hello")))
        )

        // Nested sub-modules that fail to initialize are properly handled
        "quxTarget" - check.checkSeq0(
          Seq("bar.qux.quxTarget"),
          res => res.isLeft && res.left.exists(_.contains("Qux Boom"))
        )
        "quxCommand" - check.checkSeq0(
          Seq("bar.qux.quxCommand", "hello"),
          res => res.isLeft && res.left.exists(_.contains("Qux Boom"))
        )
      }

      "dependency" - {
        val check = new Checker(moduleDependencyInitError)
        def isShortFooTrace(res: Either[String, List[NamedTask[_]]]) = {
          res.isLeft &&
          res.left.exists(_.contains("Foo Boom") &&
          // Make sure the stack traces are truncated and short-ish, and do not
          // contain the entire Mill internal call stack at point of failure
          res.left.exists(_.linesIterator.size < 20))
        }
        "fooTarget" - check.checkSeq0(
          Seq("foo.fooTarget"),
          isShortFooTrace
        )
        "fooCommand" - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortFooTrace
        )
        // Even though the `bar` module doesn't throw, `barTarget` and
        // `barCommand` depend on the `fooTarget` and `fooCommand` tasks on the
        // `foo` module, and the `foo` module blows up. This should turn up as
        // a stack trace when we try to resolve bar
        "barTarget" - check.checkSeq0(
          Seq("bar.barTarget"),
          isShortFooTrace
        )
        "barCommand" - check.checkSeq0(
          Seq("bar.barCommand", "hello"),
          isShortFooTrace
        )
      }

      "cross" - {
        "simple" - {
          val check = new Checker(crossModuleSimpleInitError)
          check.checkSeq0(
            Seq("myCross[1].foo"),
            res => res.isLeft && res.left.exists(_.contains("MyCross Boom"))
          )
        }
        "partial" - {
          val check = new Checker(crossModulePartialInitError)

          // If any one of the cross modules fails to initialize, even if it's
          // not the one you are asking for, we fail and make sure to catch and
          // handle the error
          test - check.checkSeq(
            Seq("myCross[1].foo"),
            Right(Set(_.myCross(1).foo))
          )
          test - check.checkSeq0(
            Seq("myCross[3].foo"),
            res => res.isLeft && res.left.exists(_.contains("MyCross Boom 3"))
          )
        }
      }
    }
  }
}
