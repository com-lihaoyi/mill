package mill.resolve

import mill.define.NamedTask
import mill.util.TestGraphs._
import utest._
object ResolveTests extends TestSuite {

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

      val resolvedTasks = resolveTasks(selectorStrings)
      assert(
        resolvedTasks.map(_.map(_.toString).toSet[String]) ==
          expected.map(_.map(_.toString))
      )

      val resolvedMetadata = resolveMetadata(selectorStrings)
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

      val resolvedTasks = resolveTasks(selectorStrings)
      assert(check(resolvedTasks))

      val resolvedMetadata = resolveMetadata(selectorStrings)
      assert(checkMetadata(resolvedMetadata))
    }

    def resolveTasks(selectorStrings: Seq[String]) = {
      Resolve.Tasks.resolve0(
        module,
        selectorStrings,
        SelectMode.Separated
      )
    }

    def resolveMetadata(selectorStrings: Seq[String]) = {
      Resolve.Segments.resolve0(
        module,
        selectorStrings,
        SelectMode.Separated
      ).map(_.map(_.render))
    }
  }

  def isShortError(x: Either[String, _], s: String) =
    x.left.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.left.exists(_.linesIterator.size < 20)

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
      "neg7" - check(
        "",
        Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
      )
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
      "neg4" - check(
        "",
        Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
      )
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
        "head" - check(
          "cross[].suffix",
          Right(Set(
            _.cross("210").suffix
          )),
          Set("cross[210].suffix")
        )
        "headNeg" - check(
          "cross[].doesntExist",
          Left(
            "Cannot resolve cross[].doesntExist. Try `mill resolve cross[]._` to see what's available."
          )
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
          "head" - check(
            "cross[].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix
            )),
            Set(
              "cross[210,jvm].suffix"
            )
          )
          "headNeg" - check(
            "cross[].doesntExist",
            Left(
              "Cannot resolve cross[].doesntExist. Try `mill resolve cross[]._` to see what's available."
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
          // "212" is the defaultCrossSegments
          "head" - check(
            "cross[].cross2[jvm].suffix",
            Right(Set(
              _.cross("212").cross2("jvm").suffix
            )),
            Set(
              "cross[212].cross2[jvm].suffix"
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

    "duplicate" - {
      val check = new Checker(duplicates)

      def segments(
          found: Either[String, List[NamedTask[_]]],
          expected: Either[String, List[NamedTask[_]]]
      ) = {
        found.map(_.map(_.ctx.segments)) == expected.map(_.map(_.ctx.segments))
      }

      "wildcard" - {
        "wrapped" - {
          "targets" - check.checkSeq0(
            Seq("__.test1"),
            _ == Right(List(duplicates.wrapper.test1.test1)),
            _ == Right(List("wrapper.test1", "wrapper.test1.test1"))
          )
          "commands" - check.checkSeq0(
            Seq("__.test2"),
            segments(_, Right(List(duplicates.wrapper.test2.test2()))),
            _ == Right(List("wrapper.test2", "wrapper.test2.test2"))
          )
        }
        "targets" - check.checkSeq0(
          Seq("__.test3"),
          _ == Right(List(duplicates.test3.test3)),
          _ == Right(List("test3", "test3.test3"))
        )
        "commands" - check.checkSeq0(
          Seq("__.test4"),
          segments(_, Right(List(duplicates.test4.test4()))),
          _ == Right(List("test4", "test4.test4"))
        )
      }

      "braces" - {
        "targets" - check.checkSeq0(
          Seq("{test3.test3,test3.test3}"),
          _ == Right(List(duplicates.test3.test3)),
          _ == Right(List("test3.test3"))
        )
        "commands" - check.checkSeq0(
          Seq("{test4,test4}"),
          segments(_, Right(List(duplicates.test4.test4()))),
          _ == Right(List("test4"))
        )
      }
      "plus" - {
        "targets" - check.checkSeq0(
          Seq("test3.test3", "+", "test3.test3"),
          _ == Right(List(duplicates.test3.test3)),
          _ == Right(List("test3.test3"))
        )
        "commands" - check.checkSeq0(
          Seq("test4", "+", "test4"),
          segments(_, Right(List(duplicates.test4.test4()))),
          _ == Right(List("test4"))
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
          Right(Set(_.rootTarget)),
          // Even though instantiating the target fails due to the module
          // failing, we can still resolve the task name, since resolving tasks
          // does not require instantiating the module
          Set("rootTarget")
        )
        "rootCommand" - check.checkSeq(
          Seq("rootCommand", "hello"),
          Right(Set(_.rootCommand("hello"))),
          Set("rootCommand")
        )

        // Resolving tasks on a module that fails to initialize is properly
        // caught and reported in the Either result
        "fooTarget" - check.checkSeq0(
          Seq("foo.fooTarget"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooTarget"))
        )
        "fooCommand" - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooCommand"))
        )

        // Sub-modules that can initialize allow tasks to be resolved, even
        // if their siblings or children are broken
        "barTarget" - check.checkSeq(
          Seq("bar.barTarget"),
          Right(Set(_.bar.barTarget)),
          Set("bar.barTarget")
        )
        "barCommand" - check.checkSeq(
          Seq("bar.barCommand", "hello"),
          Right(Set(_.bar.barCommand("hello"))),
          Set("bar.barCommand")
        )

        // Nested sub-modules that fail to initialize are properly handled
        "quxTarget" - check.checkSeq0(
          Seq("bar.qux.quxTarget"),
          isShortError(_, "Qux Boom"),
          _ == Right(List("bar.qux.quxTarget"))
        )
        "quxCommand" - check.checkSeq0(
          Seq("bar.qux.quxCommand", "hello"),
          isShortError(_, "Qux Boom"),
          _ == Right(List("bar.qux.quxCommand"))
        )
      }

      "dependency" - {
        val check = new Checker(moduleDependencyInitError)

        "fooTarget" - check.checkSeq0(
          Seq("foo.fooTarget"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooTarget"))
        )
        "fooCommand" - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooCommand"))
        )
        // Even though the `bar` module doesn't throw, `barTarget` and
        // `barCommand` depend on the `fooTarget` and `fooCommand` tasks on the
        // `foo` module, and the `foo` module blows up. This should turn up as
        // a stack trace when we try to resolve bar
        "barTarget" - check.checkSeq0(
          Seq("bar.barTarget"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("bar.barTarget"))
        )
        "barCommand" - check.checkSeq0(
          Seq("bar.barCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("bar.barCommand"))
        )
      }

      "cross" - {

        "simple" - {
          val check = new Checker(crossModuleSimpleInitError)
          check.checkSeq0(
            Seq("myCross[1].foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
          check.checkSeq0(
            Seq("__.foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
          check.checkSeq0(
            Seq("__"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
        }
        "partial" - {
          val check = new Checker(crossModulePartialInitError)

          // If any one of the cross modules fails to initialize, even if it's
          // not the one you are asking for, we fail and make sure to catch and
          // handle the error
          test - check.checkSeq(
            Seq("myCross[1].foo"),
            Right(Set(_.myCross(1).foo)),
            Set("myCross[1].foo")
          )
          test - check.checkSeq0(
            Seq("myCross[3].foo"),
            isShortError(_, "MyCross Boom 3"),
            isShortError(_, "MyCross Boom 3")
          )
          // Using wildcards forces evaluation of the myCross submodules,
          // causing failure if we try to instantiate the tasks, but just
          // resolving the tasks is fine since we don't need to instantiate
          // the sub-modules to resolve their paths
          test - check.checkSeq0(
            Seq("myCross._.foo"),
            isShortError(_, "MyCross Boom"),
            _ == Right(List("myCross[1].foo", "myCross[2].foo", "myCross[3].foo", "myCross[4].foo"))
          )
          test - check.checkSeq0(
            Seq("myCross[_].foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
          test - check.checkSeq0(
            Seq("__.foo"),
            isShortError(_, "MyCross Boom"),
            _ == Right(List("myCross[1].foo", "myCross[2].foo", "myCross[3].foo", "myCross[4].foo"))
          )
          test - check.checkSeq0(
            Seq("__"),
            isShortError(_, "MyCross Boom"),
            _ == Right(List(
              "",
              "myCross",
              "myCross[1]",
              "myCross[1].foo",
              "myCross[2]",
              "myCross[2].foo",
              "myCross[3]",
              "myCross[3].foo",
              "myCross[4]",
              "myCross[4].foo"
            ))
          )
        }
        "self" - {
          val check = new Checker(crossModuleSelfInitError)

          // When the cross module itself fails to initialize, even before its
          // children get a chance to init. Instantiating the tasks fails, but
          // resolving the task paths fails too, since finding the valid cross
          // values requires instantiating the cross module. Make sure both
          // fail as expected, and that their exception is properly wrapped.
          test - check.checkSeq0(
            Seq("myCross[3].foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )

          test - check.checkSeq0(
            Seq("myCross._.foo"),
            isShortError(_, "MyCross Boom"),
            isShortError(_, "MyCross Boom")
          )
        }

        "parent" - {
          val check = new Checker(crossModuleParentInitError)

          // When the parent of the cross module fails to initialize, even
          // before the cross module or its children get a chance to init,
          // ensure we handle the error properly
          test - check.checkSeq0(
            Seq("parent.myCross[3].foo"),
            isShortError(_, "Parent Boom"),
            isShortError(_, "Parent Boom")
          )

          test - check.checkSeq0(
            Seq("parent.myCross._.foo"),
            isShortError(_, "Parent Boom"),
            isShortError(_, "Parent Boom")
          )
        }
      }
    }

    test("overridenModule") {
      val check = new Checker(overrideModule)
      test - check(
        "sub.inner.subTarget",
        Right(Set(_.sub.inner.subTarget)),
        Set("sub.inner.subTarget")
      )
      test - check(
        "sub.inner.baseTarget",
        Right(Set(_.sub.inner.baseTarget)),
        Set("sub.inner.baseTarget")
      )
    }
    test("dynamicModule") {
      val check = new Checker(dynamicModule)
      test - check(
        "normal.inner.target",
        Right(Set(_.normal.inner.target)),
        Set("normal.inner.target")
      )
      test - check(
        "normal._.target",
        Right(Set(_.normal.inner.target)),
        Set("normal.inner.target")
      )
      test - check(
        "niled.inner.target",
        Left(
          "Cannot resolve niled.inner.target. Try `mill resolve niled._` to see what's available."
        ),
        Set()
      )
      test - check(
        "niled._.target",
        Left("Cannot resolve niled._.target. Try `mill resolve niled._` to see what's available."),
        Set()
      )
      test - check(
        "__.target",
        Right(Set(_.normal.inner.target)),
        Set("normal.inner.target")
      )
    }
  }
}
