package mill.resolve

import mill.define.{Discover, NamedTask, TaskModule, ModuleRef}
import mill.util.TestGraphs
import mill.util.TestGraphs._
import mill.testkit.TestBaseModule
import mill.{Task, Module, Cross}
import utest._
object ResolveTests extends TestSuite {

  object doubleNestedModule extends TestBaseModule {
    def single = Task { 5 }
    object nested extends Module {
      def single = Task { 7 }

      object inner extends Module {
        def single = Task { 9 }
      }
    }
    def millDiscover = Discover[this.type]
  }

  object duplicates extends TestBaseModule {
    object wrapper extends Module {
      object test1 extends Module {
        def test1 = Task {}
      }

      object test2 extends TaskModule {
        override def defaultCommandName() = "test2"

        def test2() = Task.Command {}
      }
    }

    object test3 extends Module {
      def test3 = Task {}
    }

    object test4 extends TaskModule {
      override def defaultCommandName() = "test4"

      def test4() = Task.Command {}
    }

    def millDiscover = Discover[this.type]
  }

  object TypedModules extends TestBaseModule {
    trait TypeA extends Module {
      def foo = Task { "foo" }
    }
    trait TypeB extends Module {
      def bar = Task { "bar" }
    }
    trait TypeC extends Module {
      def baz = Task { "baz" }
    }
    trait TypeAB extends TypeA with TypeB

    object typeA extends TypeA
    object typeB extends TypeB
    object typeC extends TypeC {
      object typeA extends TypeA
    }
    object typeAB extends TypeAB
    def millDiscover = Discover[this.type]
  }

  object TypedCrossModules extends TestBaseModule {
    trait TypeA extends Cross.Module[String] {
      def foo = Task { crossValue }
    }

    trait TypeB extends Module {
      def bar = Task { "bar" }
    }

    trait TypeAB extends TypeA with TypeB

    object typeA extends Cross[TypeA]("a", "b")
    object typeAB extends Cross[TypeAB]("a", "b")

    object inner extends Module {
      object typeA extends Cross[TypeA]("a", "b")
      object typeAB extends Cross[TypeAB]("a", "b")
    }

    trait NestedAB extends TypeAB {
      object typeAB extends Cross[TypeAB]("a", "b")
    }
    object nestedAB extends Cross[NestedAB]("a", "b")
    def millDiscover = Discover[this.type]
  }

  object TypedInnerModules extends TestBaseModule {
    trait TypeA extends Module {
      def foo = Task { "foo" }
    }
    object typeA extends TypeA
    object typeB extends Module {
      def foo = Task { "foo" }
    }
    object inner extends Module {
      trait TypeA extends Module {
        def foo = Task { "foo" }
      }
      object typeA extends TypeA
    }
    def millDiscover = Discover[this.type]
  }

  object AbstractModule extends TestBaseModule {
    trait Abstract extends Module {
      lazy val tests: Tests = new Tests {}
      trait Tests extends Module {}
    }

    object concrete extends Abstract {
      override lazy val tests: ConcreteTests = new ConcreteTests {}
      trait ConcreteTests extends Tests {
        object inner extends Module {
          def foo = Task { "foo" }
          object innerer extends Module {
            def bar = Task { "bar" }
          }
        }
      }
    }
    def millDiscover = Discover[this.type]
  }

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
      val check = new Checker(TypedModules)
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
      val check = new Checker(TypedCrossModules)
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
        val check = new Checker(TypedInnerModules)
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
      val check = new Checker(AbstractModule)
      test - check(
        "__",
        Right(Set(_.concrete.tests.inner.foo, _.concrete.tests.inner.innerer.bar))
      )
    }
  }
}
