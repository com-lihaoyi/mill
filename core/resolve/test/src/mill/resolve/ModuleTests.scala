package mill.resolve

import mill.api.Result
import mill.api.{Discover, ModuleRef, Task, DefaultTaskModule}
import mill.testkit.TestRootModule
import mill.api.DynamicModule
import mill.api.TestGraphs.*
import mill.{Cross, Module}
import utest.*

object ModuleTests extends TestSuite {

  object duplicates extends TestRootModule {
    object wrapper extends Module {
      object test1 extends Module {
        def test1 = Task {}
      }

      object test2 extends DefaultTaskModule {
        override def defaultTask() = "test2"

        def test2() = Task.Command {}
      }
    }

    object test3 extends Module {
      def test3 = Task {}
    }

    object test4 extends DefaultTaskModule {
      override def defaultTask() = "test4"

      def test4() = Task.Command {}
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TypedModules extends TestRootModule {
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
    lazy val millDiscover = Discover[this.type]
  }

  object TypedCrossModules extends TestRootModule {
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
    lazy val millDiscover = Discover[this.type]
  }

  object TypedInnerModules extends TestRootModule {
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
    lazy val millDiscover = Discover[this.type]
  }

  object AbstractModule extends TestRootModule {
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
    lazy val millDiscover = Discover[this.type]
  }

  object overrideModule extends TestRootModule {
    trait Base extends Module {
      lazy val inner: BaseInnerModule = new BaseInnerModule {}
      lazy val ignored: ModuleRef[BaseInnerModule] = ModuleRef(new BaseInnerModule {})
      trait BaseInnerModule extends mill.api.Module {
        def baseTask = Task { 1 }
      }
    }
    object sub extends Base {
      override lazy val inner: SubInnerModule = new SubInnerModule {}
      override lazy val ignored: ModuleRef[SubInnerModule] = ModuleRef(new SubInnerModule {})
      trait SubInnerModule extends BaseInnerModule {
        def subTask = Task { 2 }
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  object dynamicModule extends TestRootModule {
    object normal extends DynamicModule {
      object inner extends Module {
        def task = Task { 1 }
      }
    }
    object niled extends DynamicModule {
      override def moduleDirectChildren: Seq[Module] = Nil
      object inner extends Module {
        def task = Task { 1 }
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  def isShortError(x: Result[?], s: String) =
    x.errorOpt.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.errorOpt.exists(_.linesIterator.size < 25)

  val tests = Tests {
    test("cross") {
      test("single") {
        val check = new Checker(singleCross)
        test("pos1") - check(
          "cross[210].suffix",
          Result.Success(Set(_.cross("210").suffix)),
          Set("cross[210].suffix")
        )
        test("pos2") - check(
          "cross[211].suffix",
          Result.Success(Set(_.cross("211").suffix)),
          Set("cross[211].suffix")
        )
        test("posCurly") - check(
          "cross[{210,211}].suffix",
          Result.Success(Set(_.cross("210").suffix, _.cross("211").suffix)),
          Set("cross[210].suffix", "cross[211].suffix")
        )
        test("neg1") - check(
          "cross[210].doesntExist",
          Result.Failure(
            "Cannot resolve cross[210].doesntExist. Try `mill resolve cross[210]._` to see what's available."
          )
        )
        test("neg2") - check(
          "cross[doesntExist].doesntExist",
          Result.Failure(
            "Cannot resolve cross[doesntExist].doesntExist. Try `mill resolve cross._` to see what's available."
          )
        )
        test("neg3") - check(
          "cross[221].doesntExist",
          Result.Failure("Cannot resolve cross[221].doesntExist. Did you mean cross[211]?")
        )
        test("neg4") - check(
          "cross[doesntExist].suffix",
          Result.Failure(
            "Cannot resolve cross[doesntExist].suffix. Try `mill resolve cross._` or `mill resolve __.suffix` to see what's available."
          )
        )
        test("wildcard") - check(
          "cross[_].suffix",
          Result.Success(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          )),
          Set("cross[210].suffix", "cross[211].suffix", "cross[212].suffix")
        )
        test("wildcard2") - check(
          "cross[__].suffix",
          Result.Success(Set(
            _.cross("210").suffix,
            _.cross("211").suffix,
            _.cross("212").suffix
          )),
          Set("cross[210].suffix", "cross[211].suffix", "cross[212].suffix")
        )
        test("head") - check(
          "cross[].suffix",
          Result.Success(Set(
            _.cross("210").suffix
          )),
          Set("cross[210].suffix")
        )
        test("headNeg") - check(
          "cross[].doesntExist",
          Result.Failure(
            "Cannot resolve cross[].doesntExist. Try `mill resolve cross[]._` to see what's available."
          )
        )
      }
      test("double") {
        val check = new Checker(doubleCross)
        test("pos1") - check(
          "cross[210,jvm].suffix",
          Result.Success(Set(_.cross("210", "jvm").suffix)),
          Set("cross[210,jvm].suffix")
        )
        test("pos2") - check(
          "cross[211,jvm].suffix",
          Result.Success(Set(_.cross("211", "jvm").suffix)),
          Set("cross[211,jvm].suffix")
        )
        test("wildcard") {
          test("labelNeg1") - check(
            "_.suffix",
            Result.Failure(
              "Cannot resolve _.suffix. Try `mill resolve _._` or `mill resolve __.suffix` to see what's available."
            )
          )
          test("labelNeg2") - check(
            "_.doesntExist",
            Result.Failure(
              "Cannot resolve _.doesntExist. Try `mill resolve _._` to see what's available."
            )
          )
          test("labelNeg3") - check(
            "__.doesntExist",
            Result.Failure(
              "Cannot resolve __.doesntExist. Try `mill resolve _` to see what's available."
            )
          )
          test("labelNeg4") - check(
            "cross.__.doesntExist",
            Result.Failure(
              "Cannot resolve cross.__.doesntExist. Try `mill resolve cross._` to see what's available."
            )
          )
          test("labelNeg5") - check(
            "cross._.doesntExist",
            Result.Failure(
              "Cannot resolve cross._.doesntExist. Try `mill resolve cross._` to see what's available."
            )
          )
          test("labelPos") - check(
            "__.suffix",
            Result.Success(Set(
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
            Result.Success(Set(
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
            Result.Success(Set(
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
            Result.Success(Set(
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
            Result.Success(Set(
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
            Result.Success(Set(
              _.cross("210", "jvm").suffix
            )),
            Set(
              "cross[210,jvm].suffix"
            )
          )
          test("headNeg") - check(
            "cross[].doesntExist",
            Result.Failure(
              "Cannot resolve cross[].doesntExist. Try `mill resolve cross[]._` to see what's available."
            )
          )
        }
      }
      test("nested") {
        val check = new Checker(nestedCrosses)
        test("pos1") - check(
          "cross[210].cross2[js].suffix",
          Result.Success(Set(_.cross("210").cross2("js").suffix)),
          Set("cross[210].cross2[js].suffix")
        )
        test("pos2") - check(
          "cross[211].cross2[jvm].suffix",
          Result.Success(Set(_.cross("211").cross2("jvm").suffix)),
          Set("cross[211].cross2[jvm].suffix")
        )
        test("pos2NoDefaultTask") - check(
          "cross[211].cross2[jvm]",
          Result.Failure("Cannot find default task to evaluate for module cross[211].cross2[jvm]")
        )
        test("wildcard") {
          test("first") - check(
            "cross[_].cross2[jvm].suffix",
            Result.Success(Set(
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
            Result.Success(Set(
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
            Result.Success(Set(
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
            Result.Success(Set(
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
          Result.Success(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        test("pos1Default") - check.checkSeq(
          Seq("cross1[210].cross2[js]"),
          Result.Success(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js]")
        )
        test("pos1WithWildcard") - check.checkSeq(
          Seq("cross1[210].cross2[js]._"),
          Result.Success(Set(_.cross1("210").cross2("js").suffixCmd())),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        test("pos1WithArgs") - check.checkSeq(
          Seq("cross1[210].cross2[js].suffixCmd", "suffix-arg"),
          Result.Success(Set(_.cross1("210").cross2("js").suffixCmd("suffix-arg"))),
          Set("cross1[210].cross2[js].suffixCmd")
        )
        test("pos2") - check.checkSeq(
          Seq("cross1[211].cross2[jvm].suffixCmd"),
          Result.Success(Set(_.cross1("211").cross2("jvm").suffixCmd())),
          Set("cross1[211].cross2[jvm].suffixCmd")
        )
        test("pos2Default") - check.checkSeq(
          Seq("cross1[211].cross2[jvm]"),
          Result.Success(Set(_.cross1("211").cross2("jvm").suffixCmd())),
          Set("cross1[211].cross2[jvm]")
        )
      }
    }

    test("duplicate") {
      val check = new Checker(duplicates)

      def segments(
          found: Result[List[Task.Named[?]]],
          expected: Result[List[Task.Named[?]]]
      ) = {
        found.map(_.map(_.ctx.segments)) == expected.map(_.map(_.ctx.segments))
      }

      test("wildcard") {
        test("wrapped") {
          test("tasks") - check.checkSeq0(
            Seq("__.test1"),
            _ == Result.Success(List(duplicates.wrapper.test1.test1)),
            _ == Result.Success(List("wrapper.test1", "wrapper.test1.test1"))
          )
          test("commands") - check.checkSeq0(
            Seq("__.test2"),
            segments(_, Result.Success(List(duplicates.wrapper.test2.test2()))),
            _ == Result.Success(List("wrapper.test2", "wrapper.test2.test2"))
          )
        }
        test("tasks") - check.checkSeq0(
          Seq("__.test3"),
          _ == Result.Success(List(duplicates.test3.test3)),
          _ == Result.Success(List("test3", "test3.test3"))
        )
        test("commands") - check.checkSeq0(
          Seq("__.test4"),
          segments(_, Result.Success(List(duplicates.test4.test4()))),
          _ == Result.Success(List("test4", "test4.test4"))
        )
      }

      test("braces") {
        test("tasks") - check.checkSeq0(
          Seq("{test3.test3,test3.test3}"),
          _ == Result.Success(List(duplicates.test3.test3)),
          _ == Result.Success(List("test3.test3"))
        )
        test("commands") - check.checkSeq0(
          Seq("{test4,test4}"),
          segments(_, Result.Success(List(duplicates.test4.test4()))),
          _ == Result.Success(List("test4"))
        )
      }
      test("plus") {
        test("tasks") - check.checkSeq0(
          Seq("test3.test3", "+", "test3.test3"),
          _ == Result.Success(List(duplicates.test3.test3)),
          _ == Result.Success(List("test3.test3"))
        )
        test("commands") - check.checkSeq0(
          Seq("test4", "+", "test4"),
          segments(_, Result.Success(List(duplicates.test4.test4()))),
          _ == Result.Success(List("test4"))
        )
      }
    }

    test("overriddenModule") {
      val check = new Checker(overrideModule)
      test - check(
        "sub.inner.subTask",
        Result.Success(Set(_.sub.inner.subTask)),
        Set("sub.inner.subTask")
      )
      test - check(
        "sub.inner.baseTask",
        Result.Success(Set(_.sub.inner.baseTask)),
        Set("sub.inner.baseTask")
      )
    }
    test("dynamicModule") {
      val check = new Checker(dynamicModule)
      test - check(
        "normal.inner.task",
        Result.Success(Set(_.normal.inner.task)),
        Set("normal.inner.task")
      )
      test - check(
        "normal._.task",
        Result.Success(Set(_.normal.inner.task)),
        Set("normal.inner.task")
      )
      test - check(
        "niled.inner.task",
        Result.Failure(
          "Cannot resolve niled.inner.task. Try `mill resolve niled._` or `mill resolve __.task` to see what's available."
        ),
        Set()
      )
      test - check(
        "niled._.task",
        Result.Failure(
          "Cannot resolve niled._.task. Try `mill resolve niled._` or `mill resolve __.task` to see what's available."
        ),
        Set()
      )
      test - check(
        "niled._.tttask",
        Result.Failure(
          "Cannot resolve niled._.tttask. Try `mill resolve niled._` or `mill resolve __.task` to see what's available."
        ),
        Set()
      )
      test - check(
        "__.task",
        Result.Success(Set(_.normal.inner.task)),
        Set("normal.inner.task")
      )
    }
    test("abstractModule") {
      val check = new Checker(AbstractModule)
      test - check(
        "__",
        Result.Success(Set(_.concrete.tests.inner.foo, _.concrete.tests.inner.innerer.bar))
      )
    }
  }
}
