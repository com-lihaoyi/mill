package mill.resolve

import mill.define.{Discover, ModuleRef, NamedTask, TaskModule}
import mill.testkit.TestBaseModule
import mill.util.TestGraphs
import mill.util.TestGraphs.*
import mill.{Cross, Module, Task}
import utest.*

object ModuleTests extends TestSuite {

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

    lazy val millDiscover = Discover[this.type]
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
    lazy val millDiscover = Discover[this.type]
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
    lazy val millDiscover = Discover[this.type]
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
    lazy val millDiscover = Discover[this.type]
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
    lazy val millDiscover = Discover[this.type]
  }

  def isShortError(x: Either[String, _], s: String) =
    x.left.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.left.exists(_.linesIterator.size < 25)

  val tests = Tests {
    val graphs = new mill.util.TestGraphs()
    import graphs.*

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
            "Cannot resolve cross[doesntExist].suffix. Try `mill resolve cross._` or `mill resolve __.suffix` to see what's available."
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
            Left(
              "Cannot resolve _.suffix. Try `mill resolve _._` or `mill resolve __.suffix` to see what's available."
            )
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
    test("abstractModule") {
      val check = new Checker(AbstractModule)
      test - check(
        "__",
        Right(Set(_.concrete.tests.inner.foo, _.concrete.tests.inner.innerer.bar))
      )
    }
  }
}
