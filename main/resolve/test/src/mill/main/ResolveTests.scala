package mill.resolve

import mill.define.NamedTask
import mill.util.TestGraphs
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
      selectorStrings.mkString(" ")
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
        SelectMode.Separated,
        false,
        false
      )
    }

    def resolveMetadata(selectorStrings: Seq[String]) = {
      Resolve.Segments.resolve0(
        module,
        selectorStrings,
        SelectMode.Separated,
        false,
        false
      ).map(_.map(_.render))
    }
  }

  def isShortError(x: Either[String, _], s: String) =
    x.left.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.left.exists(_.linesIterator.size < 25)

  val tests = Tests {
    val graphs = new mill.util.TestGraphs()
    import graphs._
    test("single") {
      val check = new Checker(singleton)
      test("pos") - check("single", Right(Set(_.single)), Set("single"))
      test("wildcard") - check("_", Right(Set(_.single)), Set("single"))
      test("neg1") - check("sngle", Left("Cannot resolve sngle. Did you mean single?"))
      test("neg2") - check("snigle", Left("Cannot resolve snigle. Did you mean single?"))
      test("neg3") - check("nsiigle", Left("Cannot resolve nsiigle. Did you mean single?"))
      test("neg4") - check(
        "ansiigle",
        Left("Cannot resolve ansiigle. Try `mill resolve _` to see what's available.")
      )
      test("neg5") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg6") - check(
        "single.doesntExist",
        Left("Cannot resolve single.doesntExist. single resolves to a Task with no children.")
      )
      test("neg7") - check(
        "",
        Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
      )
    }
    test("backtickIdentifiers") {
      val check = new Checker(bactickIdentifiers)
      test("pos1") - check("up-target", Right(Set(_.`up-target`)), Set("up-target"))
      test("pos2") - check("a-down-target", Right(Set(_.`a-down-target`)), Set("a-down-target"))
      test("neg1") - check("uptarget", Left("Cannot resolve uptarget. Did you mean up-target?"))
      test("neg2") - check("upt-arget", Left("Cannot resolve upt-arget. Did you mean up-target?"))
      test("neg3") - check(
        "up-target.doesntExist",
        Left("Cannot resolve up-target.doesntExist. up-target resolves to a Task with no children.")
      )
      test("neg4") - check(
        "",
        Left("Target selector must not be empty. Try `mill resolve _` to see what's available.")
      )
      test("neg5") - check(
        "invisible",
        Left("Cannot resolve invisible. Try `mill resolve _` to see what's available.")
      )
      test("negBadParse") - check(
        "invisible&",
        Left("Parsing exception Position 1:10, found \"&\"")
      )
      test("nested") {
        test("pos") - check(
          "nested-module.nested-target",
          Right(Set(_.`nested-module`.`nested-target`)),
          Set("nested-module.nested-target")
        )
        test("neg") - check(
          "nested-module.doesntExist",
          Left(
            "Cannot resolve nested-module.doesntExist. Try `mill resolve nested-module._` to see what's available."
          )
        )
      }
    }
    test("nested") {
      val check = new Checker(nestedModule)
      test("pos1") - check("single", Right(Set(_.single)), Set("single"))
      test("pos2") - check("nested.single", Right(Set(_.nested.single)), Set("nested.single"))
      test("pos3") - check(
        "classInstance.single",
        Right(Set(_.classInstance.single)),
        Set("classInstance.single")
      )
      test("posCurly1") - check(
        "{nested,classInstance}.single",
        Right(Set(_.nested.single, _.classInstance.single)),
        Set("nested.single", "classInstance.single")
      )
      test("posCurly2") - check(
        "{single,{nested,classInstance}.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("posCurly3") - check(
        "{single,nested.single,classInstance.single}",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("posCurly4") - check(
        "{,nested.,classInstance.}single",
        Right(Set(_.single, _.nested.single, _.classInstance.single)),
        Set("single", "nested.single", "classInstance.single")
      )
      test("neg1") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "single.doesntExist",
        Left("Cannot resolve single.doesntExist. single resolves to a Task with no children.")
      )
      test("neg3") - check(
        "nested.doesntExist",
        Left(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      test("neg3") - check(
        "nested.singel",
        Left("Cannot resolve nested.singel. Did you mean nested.single?")
      )
      test("neg4") - check(
        "classInstance.doesntExist",
        Left(
          "Cannot resolve classInstance.doesntExist. Try `mill resolve classInstance._` to see what's available."
        )
      )
      test("wildcard") - check(
        "_.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
      test("wildcardNeg") - check(
        "_._.single",
        Left("Cannot resolve _._.single. Try `mill resolve _` to see what's available.")
      )
      test("wildcardNeg2") - check(
        "_._.__",
        Left("Cannot resolve _._.__. Try `mill resolve _` to see what's available.")
      )
      test("wildcardNeg3") - check(
        "nested._.foobar",
        Left("Cannot resolve nested._.foobar. nested._ resolves to a Task with no children.")
      )
      test("wildcard2") - check(
        "__.single",
        Right(Set(
          _.single,
          _.classInstance.single,
          _.nested.single
        )),
        Set("single", "nested.single", "classInstance.single")
      )

      test("wildcard3") - check(
        "_.__.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single
        )),
        Set("nested.single", "classInstance.single")
      )
      test("wildcard4") - check(
        "__.__.single",
        Right(Set(
          _.classInstance.single,
          _.nested.single,
          _.single
        )),
        Set("nested.single", "classInstance.single", "single")
      )
    }
    test("doubleNested") {
      val check = new Checker(doubleNestedModule)
      test("pos1") - check("single", Right(Set(_.single)), Set("single"))
      test("pos2") - check("nested.single", Right(Set(_.nested.single)), Set("nested.single"))
      test("pos3") - check(
        "nested.inner.single",
        Right(Set(_.nested.inner.single)),
        Set("nested.inner.single")
      )
      test("neg1") - check(
        "doesntExist",
        Left("Cannot resolve doesntExist. Try `mill resolve _` to see what's available.")
      )
      test("neg2") - check(
        "nested.doesntExist",
        Left(
          "Cannot resolve nested.doesntExist. Try `mill resolve nested._` to see what's available."
        )
      )
      test("neg3") - check(
        "nested.inner.doesntExist",
        Left(
          "Cannot resolve nested.inner.doesntExist. Try `mill resolve nested.inner._` to see what's available."
        )
      )
      test("neg4") - check(
        "nested.inner.doesntExist.alsoDoesntExist2",
        Left(
          "Cannot resolve nested.inner.doesntExist.alsoDoesntExist2. Try `mill resolve nested.inner._` to see what's available."
        )
      )
    }

    test("cross") {
      test("single") {
        val check = new Checker(singleCross)
        test("pos1") - check(
          "cross[210].suffix",
          Right(Set(_.cross("210").suffix)),
          Set("cross[210].suffix")
        )
        test("pos2") - check(
          "cross[211].suffix",
          Right(Set(_.cross("211").suffix)),
          Set("cross[211].suffix")
        )
        test("posCurly") - check(
          "cross[{210,211}].suffix",
          Right(Set(_.cross("210").suffix, _.cross("211").suffix)),
          Set("cross[210].suffix", "cross[211].suffix")
        )
        test("neg1") - check(
          "cross[210].doesntExist",
          Left(
            "Cannot resolve cross[210].doesntExist. Try `mill resolve cross[210]._` to see what's available."
          )
        )
        test("neg2") - check(
          "cross[doesntExist].doesntExist",
          Left(
            "Cannot resolve cross[doesntExist].doesntExist. Try `mill resolve cross._` to see what's available."
          )
        )
        test("neg3") - check(
          "cross[221].doesntExist",
          Left("Cannot resolve cross[221].doesntExist. Did you mean cross[211]?")
        )
        test("neg4") - check(
          "cross[doesntExist].suffix",
          Left(
            "Cannot resolve cross[doesntExist].suffix. Try `mill resolve cross._` to see what's available."
          )
        )
        test("wildcard") - check(
          "cross[_].suffix",
          Right(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          )),
          Set("cross[210].suffix", "cross[211].suffix", "cross[212].suffix")
        )
        test("wildcard2") - check(
          "cross[__].suffix",
          Right(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          )),
          Set("cross[210].suffix", "cross[211].suffix", "cross[212].suffix")
        )
        test("head") - check(
          "cross[].suffix",
          Right(Set(
            _.cross("210").suffix
          )),
          Set("cross[210].suffix")
        )
        test("headNeg") - check(
          "cross[].doesntExist",
          Left(
            "Cannot resolve cross[].doesntExist. Try `mill resolve cross[]._` to see what's available."
          )
        )
      }
      test("double") {
        val check = new Checker(doubleCross)
        test("pos1") - check(
          "cross[210,jvm].suffix",
          Right(Set(_.cross("210", "jvm").suffix)),
          Set("cross[210,jvm].suffix")
        )
        test("pos2") - check(
          "cross[211,jvm].suffix",
          Right(Set(_.cross("211", "jvm").suffix)),
          Set("cross[211,jvm].suffix")
        )
        test("wildcard") {
          test("labelNeg1") - check(
            "_.suffix",
            Left("Cannot resolve _.suffix. Try `mill resolve _._` to see what's available.")
          )
          test("labelNeg2") - check(
            "_.doesntExist",
            Left(
              "Cannot resolve _.doesntExist. Try `mill resolve _._` to see what's available."
            )
          )
          test("labelNeg3") - check(
            "__.doesntExist",
            Left("Cannot resolve __.doesntExist. Try `mill resolve _` to see what's available.")
          )
          test("labelNeg4") - check(
            "cross.__.doesntExist",
            Left(
              "Cannot resolve cross.__.doesntExist. Try `mill resolve cross._` to see what's available."
            )
          )
          test("labelNeg5") - check(
            "cross._.doesntExist",
            Left(
              "Cannot resolve cross._.doesntExist. Try `mill resolve cross._` to see what's available."
            )
          )
          test("labelPos") - check(
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
          test("first") - check(
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
          test("second") - check(
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
          test("both") - check(
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
          test("both2") - check(
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
          test("head") - check(
            "cross[].suffix",
            Right(Set(
              _.cross("210", "jvm").suffix
            )),
            Set(
              "cross[210,jvm].suffix"
            )
          )
          test("headNeg") - check(
            "cross[].doesntExist",
            Left(
              "Cannot resolve cross[].doesntExist. Try `mill resolve cross[]._` to see what's available."
            )
          )
        }
      }
      test("nested") {
        val check = new Checker(nestedCrosses)
        test("pos1") - check(
          "cross[210].cross2[js].suffix",
          Right(Set(_.cross("210").cross2("js").suffix)),
          Set("cross[210].cross2[js].suffix")
        )
        test("pos2") - check(
          "cross[211].cross2[jvm].suffix",
          Right(Set(_.cross("211").cross2("jvm").suffix)),
          Set("cross[211].cross2[jvm].suffix")
        )
        test("pos2NoDefaultTask") - check(
          "cross[211].cross2[jvm]",
          Left("Cannot find default task to evaluate for module cross[211].cross2[jvm]")
        )
        test("wildcard") {
          test("first") - check(
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
          test("second") - check(
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
          test("both") - check(
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
          test("head") - check(
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

      test("nestedCrossTaskModule") {
        val check = new Checker(nestedTaskCrosses)
        test("pos1") - check.checkSeq(
          Seq("cross1[210].cross2[js].suffixCmd"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        test("pos1Default") - check.checkSeq(
          Seq("cross1[210].cross2[js]"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js]")
        )
        test("pos1WithWildcard") - check.checkSeq(
          Seq("cross1[210].cross2[js]._"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        test("pos1WithArgs") - check.checkSeq(
          Seq("cross1[210].cross2[js].suffixCmd", "suffix-arg"),
          Right(Set(_.cross1("210").cross2("js").suffixCmd("suffix-arg"))),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        test("pos2") - check.checkSeq(
          Seq("cross1[211].cross2[jvm].suffixCmd"),
          Right(Set(_.cross1("211").cross2("jvm").suffixCmd())),
          Set("cross1[211].cross2[jvm].suffixCmd")
        )
        test("pos2Default") - check.checkSeq(
          Seq("cross1[211].cross2[jvm]"),
          Right(Set(_.cross1("211").cross2("jvm").suffixCmd())),
          Set("cross1[211].cross2[jvm]")
        )
      }
    }

    test("duplicate") {
      val check = new Checker(duplicates)

      def segments(
          found: Either[String, List[NamedTask[_]]],
          expected: Either[String, List[NamedTask[_]]]
      ) = {
        found.map(_.map(_.ctx.segments)) == expected.map(_.map(_.ctx.segments))
      }

      test("wildcard") {
        test("wrapped") {
          test("targets") - check.checkSeq0(
            Seq("__.test1"),
            _ == Right(List(duplicates.wrapper.test1.test1)),
            _ == Right(List("wrapper.test1", "wrapper.test1.test1"))
          )
          test("commands") - check.checkSeq0(
            Seq("__.test2"),
            segments(_, Right(List(duplicates.wrapper.test2.test2()))),
            _ == Right(List("wrapper.test2", "wrapper.test2.test2"))
          )
        }
        test("targets") - check.checkSeq0(
          Seq("__.test3"),
          _ == Right(List(duplicates.test3.test3)),
          _ == Right(List("test3", "test3.test3"))
        )
        test("commands") - check.checkSeq0(
          Seq("__.test4"),
          segments(_, Right(List(duplicates.test4.test4()))),
          _ == Right(List("test4", "test4.test4"))
        )
      }

      test("braces") {
        test("targets") - check.checkSeq0(
          Seq("{test3.test3,test3.test3}"),
          _ == Right(List(duplicates.test3.test3)),
          _ == Right(List("test3.test3"))
        )
        test("commands") - check.checkSeq0(
          Seq("{test4,test4}"),
          segments(_, Right(List(duplicates.test4.test4()))),
          _ == Right(List("test4"))
        )
      }
      test("plus") {
        test("targets") - check.checkSeq0(
          Seq("test3.test3", "+", "test3.test3"),
          _ == Right(List(duplicates.test3.test3)),
          _ == Right(List("test3.test3"))
        )
        test("commands") - check.checkSeq0(
          Seq("test4", "+", "test4"),
          segments(_, Right(List(duplicates.test4.test4()))),
          _ == Right(List("test4"))
        )
      }
    }

    test("moduleInitError") {
      test("simple") {
        val check = new Checker(moduleInitError)
        // We can resolve the root module tasks even when the
        // sub-modules fail to initialize
        test("rootTarget") - check.checkSeq(
          Seq("rootTarget"),
          Right(Set(_.rootTarget)),
          // Even though instantiating the target fails due to the module
          // failing, we can still resolve the task name, since resolving tasks
          // does not require instantiating the module
          Set("rootTarget")
        )
        test("rootCommand") - check.checkSeq(
          Seq("rootCommand", "hello"),
          Right(Set(_.rootCommand("hello"))),
          Set("rootCommand")
        )

        // Resolving tasks on a module that fails to initialize is properly
        // caught and reported in the Either result
        test("fooTarget") - check.checkSeq0(
          Seq("foo.fooTarget"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooTarget"))
        )
        test("fooCommand") - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooCommand"))
        )

        // Sub-modules that can initialize allow tasks to be resolved, even
        // if their siblings or children are broken
        test("barTarget") - check.checkSeq(
          Seq("bar.barTarget"),
          Right(Set(_.bar.barTarget)),
          Set("bar.barTarget")
        )
        test("barCommand") - check.checkSeq(
          Seq("bar.barCommand", "hello"),
          Right(Set(_.bar.barCommand("hello"))),
          Set("bar.barCommand")
        )

        // Nested sub-modules that fail to initialize are properly handled
        test("quxTarget") - check.checkSeq0(
          Seq("bar.qux.quxTarget"),
          isShortError(_, "Qux Boom"),
          _ == Right(List("bar.qux.quxTarget"))
        )
        test("quxCommand") - check.checkSeq0(
          Seq("bar.qux.quxCommand", "hello"),
          isShortError(_, "Qux Boom"),
          _ == Right(List("bar.qux.quxCommand"))
        )
      }

      test("dependency") {
        val check = new Checker(moduleDependencyInitError)

        test("fooTarget") - check.checkSeq0(
          Seq("foo.fooTarget"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooTarget"))
        )
        test("fooCommand") - check.checkSeq0(
          Seq("foo.fooCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("foo.fooCommand"))
        )
        // Even though the `bar` module doesn't throw, `barTarget` and
        // `barCommand` depend on the `fooTarget` and `fooCommand` tasks on the
        // `foo` module, and the `foo` module blows up. This should turn up as
        // a stack trace when we try to resolve bar
        test("barTarget") - check.checkSeq0(
          Seq("bar.barTarget"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("bar.barTarget"))
        )
        test("barCommand") - check.checkSeq0(
          Seq("bar.barCommand", "hello"),
          isShortError(_, "Foo Boom"),
          _ == Right(List("bar.barCommand"))
        )
      }

      test("cross") {

        test("simple") {
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
        test("partial") {
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
        test("self") {
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

        test("parent") {
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

    test("overriddenModule") {
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
          "Cannot resolve niled.inner.target. Try `mill resolve niled._` or `mill resolve __.target` to see what's available."
        ),
        Set()
      )
      test - check(
        "niled._.target",
        Left(
          "Cannot resolve niled._.target. Try `mill resolve niled._` or `mill resolve __.target` to see what's available."
        ),
        Set()
      )
      test - check(
        "niled._.tttarget",
        Left(
          "Cannot resolve niled._.tttarget. Try `mill resolve niled._` or `mill resolve __.target` to see what's available."
        ),
        Set()
      )
      test - check(
        "__.target",
        Right(Set(_.normal.inner.target)),
        Set("normal.inner.target")
      )
    }
    test("typeSelector") {
      val check = new Checker(TestGraphs.TypedModules)
      test - check(
        "__",
        Right(Set(
          _.typeA.foo,
          _.typeB.bar,
          _.typeAB.foo,
          _.typeAB.bar,
          _.typeC.baz,
          _.typeC.typeA.foo
        ))
      )
      // parens should work
      test - check(
        "(__)",
        Right(Set(
          _.typeA.foo,
          _.typeB.bar,
          _.typeAB.foo,
          _.typeAB.bar,
          _.typeC.baz,
          _.typeC.typeA.foo
        ))
      )
      test - check(
        "(_)._",
        Right(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - check(
        "_:Module._",
        Right(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - {
        val res = check.resolveMetadata(Seq("__:Module"))
        assert(res == Right(List("", "typeA", "typeAB", "typeB", "typeC", "typeC.typeA")))
      }
      test - {
        val res = check.resolveMetadata(Seq("_:Module"))
        assert(res == Right(List("typeA", "typeAB", "typeB", "typeC")))
      }

      // parens should work
      test - check(
        "(_:Module)._",
        Right(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - check(
        "(_:_root_.mill.define.Module)._",
        Right(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - check(
        "(_:^_root_.mill.define.Module)._",
        Left(
          "Cannot resolve _:^_root_.mill.define.Module._. Try `mill resolve _` to see what's available."
        )
      )
      test - check(
        "_:TypeA._",
        Right(Set(_.typeA.foo, _.typeAB.foo, _.typeAB.bar))
      )
      test - check(
        "__:Module._",
        Right(Set(
          _.typeA.foo,
          _.typeB.bar,
          _.typeAB.foo,
          _.typeAB.bar,
          _.typeC.baz,
          _.typeC.typeA.foo
        ))
      )
      test - check(
        "__:TypeA._",
        Right(Set(_.typeA.foo, _.typeAB.foo, _.typeAB.bar, _.typeC.typeA.foo))
      )
      test - check(
        "(__:TypeA:^TypedModules.TypeB)._",
        Right(Set(_.typeA.foo, _.typeC.typeA.foo))
      )
      // missing parens
      test - check(
        "__:TypeA:^TypedModules.TypeB._",
        Left(
          "Cannot resolve __:TypeA:^TypedModules.TypeB._. Try `mill resolve _` to see what's available."
        )
      )
      test - check(
        "(__:TypeA:!TypeB)._",
        Right(Set(_.typeA.foo, _.typeC.typeA.foo))
      )
      test - check(
        "(__:TypedModules.TypeA:^TypedModules.TypeB)._",
        Right(Set(_.typeA.foo, _.typeC.typeA.foo))
      )
      test - check(
        "__:^TypeA._",
        Right(Set(_.typeB.bar, _.typeC.baz))
      )
      test - check(
        "__:^TypeA._",
        Right(Set(_.typeB.bar, _.typeC.baz))
      )
    }
    test("crossTypeSelector") {
      val check = new Checker(TestGraphs.TypedCrossModules)
      test - check(
        "__",
        Right(Set(
          _.typeA("a").foo,
          _.typeA("b").foo,
          _.typeAB("a").foo,
          _.typeAB("a").bar,
          _.typeAB("b").foo,
          _.typeAB("b").bar,
          _.inner.typeA("a").foo,
          _.inner.typeA("b").foo,
          _.inner.typeAB("a").foo,
          _.inner.typeAB("a").bar,
          _.inner.typeAB("b").foo,
          _.inner.typeAB("b").bar,
          _.nestedAB("a").foo,
          _.nestedAB("a").bar,
          _.nestedAB("b").foo,
          _.nestedAB("b").bar,
          _.nestedAB("a").typeAB("a").foo,
          _.nestedAB("a").typeAB("a").bar,
          _.nestedAB("a").typeAB("b").foo,
          _.nestedAB("a").typeAB("b").bar,
          _.nestedAB("b").typeAB("a").foo,
          _.nestedAB("b").typeAB("a").bar,
          _.nestedAB("b").typeAB("b").foo,
          _.nestedAB("b").typeAB("b").bar
        ))
      )
      test - check(
        "__:TypeB._",
        Right(Set(
          _.typeAB("a").foo,
          _.typeAB("a").bar,
          _.typeAB("b").foo,
          _.typeAB("b").bar,
          _.inner.typeAB("a").foo,
          _.inner.typeAB("a").bar,
          _.inner.typeAB("b").foo,
          _.inner.typeAB("b").bar,
          _.nestedAB("a").foo,
          _.nestedAB("a").bar,
          _.nestedAB("b").foo,
          _.nestedAB("b").bar,
          _.nestedAB("a").typeAB("a").foo,
          _.nestedAB("a").typeAB("a").bar,
          _.nestedAB("a").typeAB("b").foo,
          _.nestedAB("a").typeAB("b").bar,
          _.nestedAB("b").typeAB("a").foo,
          _.nestedAB("b").typeAB("a").bar,
          _.nestedAB("b").typeAB("b").foo,
          _.nestedAB("b").typeAB("b").bar
        ))
      )
      test - check(
        "__:^TypeB._",
        Right(Set(
          _.typeA("a").foo,
          _.typeA("b").foo,
          _.inner.typeA("a").foo,
          _.inner.typeA("b").foo
        ))
      )
      // Keep `!` as alternative to `^`
      test - check(
        "__:!TypeB._",
        Right(Set(
          _.typeA("a").foo,
          _.typeA("b").foo,
          _.inner.typeA("a").foo,
          _.inner.typeA("b").foo
        ))
      )
      test("innerTypeSelector") {
        val check = new Checker(TestGraphs.TypedInnerModules)
        test - check(
          "__:TypeA._",
          Right(Set(
            _.typeA.foo,
            _.inner.typeA.foo
          ))
        )
        test - check(
          "__:^TypeA._",
          Right(Set(
            _.typeB.foo
          ))
        )
        test - check(
          "(__:^inner.TypeA)._",
          Right(Set(
            _.typeA.foo,
            _.typeB.foo
          ))
        )
      }
    }
    test("abstractModule") {
      val check = new Checker(TestGraphs.AbstractModule)
      test - check(
        "__",
        Right(Set(_.concrete.tests.inner.foo, _.concrete.tests.inner.innerer.bar))
      )
    }
    test("cyclicModuleRefInitError") {
      val check = new Checker(TestGraphs.CyclicModuleRefInitError)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at myA.a,")
      )
      test - check(
        "_",
        Right(Set(_.foo))
      )
      test - check.checkSeq0(
        Seq("myA.__"),
        isShortError(_, "Cyclic module reference detected at myA.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a.__"),
        isShortError(_, "Cyclic module reference detected at myA.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a._"),
        isShortError(_, "Cyclic module reference detected at myA.a.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a._.a"),
        isShortError(_, "Cyclic module reference detected at myA.a.a,")
      )
      test - check.checkSeq0(
        Seq("myA.a.b.a"),
        isShortError(_, "Cyclic module reference detected at myA.a.b.a,")
      )
    }
    test("cyclicModuleRefInitError2") {
      val check = new Checker(TestGraphs.CyclicModuleRefInitError2)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at A.myA.a,")
      )
    }
    test("cyclicModuleRefInitError3") {
      val check = new Checker(TestGraphs.CyclicModuleRefInitError3)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at A.b.a,")
      )
      test - check.checkSeq0(
        Seq("A.__"),
        isShortError(_, "Cyclic module reference detected at A.b.a,")
      )
      test - check.checkSeq0(
        Seq("A.b.__.a.b"),
        isShortError(_, "Cyclic module reference detected at A.b.a,")
      )
    }
    test("crossedCyclicModuleRefInitError") {
      val check = new Checker(TestGraphs.CrossedCyclicModuleRefInitError)
      test - check.checkSeq0(
        Seq("__"),
        isShortError(_, "Cyclic module reference detected at cross[210].c2[210].c1,")
      )
    }
    test("nonCyclicModules") {
      val check = new Checker(TestGraphs.NonCyclicModules)
      test - check(
        "__",
        Right(Set(_.foo))
      )
    }
    test("moduleRefWithNonModuleRefChild") {
      val check = new Checker(TestGraphs.ModuleRefWithNonModuleRefChild)
      test - check(
        "__",
        Right(Set(_.foo))
      )
    }
    test("moduleRefCycle") {
      val check = new Checker(TestGraphs.ModuleRefCycle)
      test - check(
        "__",
        Right(Set(_.foo))
      )
      test - check(
        "__._",
        Right(Set(_.foo))
      )
    }
  }
}
