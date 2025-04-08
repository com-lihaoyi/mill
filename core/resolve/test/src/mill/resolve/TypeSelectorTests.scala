package mill.resolve

import mill.define.Discover
import mill.api.Result
import mill.testkit.TestBaseModule
import mill.{Cross, Module, Task}
import utest.*

object TypeSelectorTests extends TestSuite {

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

  def isShortError(x: Result[?], s: String) =
    x.errorOpt.exists(_.contains(s)) &&
      // Make sure the stack traces are truncated and short-ish, and do not
      // contain the entire Mill internal call stack at point of failure
      x.errorOpt.exists(_.linesIterator.size < 25)

  val tests = Tests {
    test("typeSelector") {
      val check = new Checker(TypedModules)
      test - check(
        "__",
        Result.Success(Set(
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
        Result.Success(Set(
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
        Result.Success(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - check(
        "_:Module._",
        Result.Success(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - {
        val res = check.resolveMetadata(Seq("__:Module"))
        assert(res == Result.Success(List("", "typeA", "typeAB", "typeB", "typeC", "typeC.typeA")))
      }
      test - {
        val res = check.resolveMetadata(Seq("_:Module"))
        assert(res == Result.Success(List("typeA", "typeAB", "typeB", "typeC")))
      }
      // parens should work
      test - check(
        "(_:Module)._",
        Result.Success(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - check(
        "(_:_root_.mill.define.Module)._",
        Result.Success(Set(_.typeA.foo, _.typeB.bar, _.typeAB.foo, _.typeAB.bar, _.typeC.baz))
      )
      test - check(
        "(_:^_root_.mill.define.Module)._",
        Result.Failure(
          "Cannot resolve _:^_root_.mill.define.Module._. Try `mill resolve _` to see what's available."
        )
      )
      test - check(
        "_:TypeA._",
        Result.Success(Set(_.typeA.foo, _.typeAB.foo, _.typeAB.bar))
      )
      test - check(
        "__:Module._",
        Result.Success(Set(
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
        Result.Success(Set(_.typeA.foo, _.typeAB.foo, _.typeAB.bar, _.typeC.typeA.foo))
      )
      test - check(
        "(__:TypeA:^TypedModules.TypeB)._",
        Result.Success(Set(_.typeA.foo, _.typeC.typeA.foo))
      )
      // missing parens
      test - check(
        "__:TypeA:^TypedModules.TypeB._",
        Result.Failure(
          "Cannot resolve __:TypeA:^TypedModules.TypeB._. Try `mill resolve _` to see what's available."
        )
      )
      test - check(
        "(__:TypeA:!TypeB)._",
        Result.Success(Set(_.typeA.foo, _.typeC.typeA.foo))
      )
      test - check(
        "(__:TypedModules.TypeA:^TypedModules.TypeB)._",
        Result.Success(Set(_.typeA.foo, _.typeC.typeA.foo))
      )
      test - check(
        "__:^TypeA._",
        Result.Success(Set(_.typeB.bar, _.typeC.baz))
      )
      test - check(
        "__:^TypeA._",
        Result.Success(Set(_.typeB.bar, _.typeC.baz))
      )
    }
    test("crossTypeSelector") {
      val check = new Checker(TypedCrossModules)
      test - check(
        "__",
        Result.Success(Set(
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
        Result.Success(Set(
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
        Result.Success(Set(
          _.typeA("a").foo,
          _.typeA("b").foo,
          _.inner.typeA("a").foo,
          _.inner.typeA("b").foo
        ))
      )
      // Keep `!` as alternative to `^`
      test - check(
        "__:!TypeB._",
        Result.Success(Set(
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
          Result.Success(Set(
            _.typeA.foo,
            _.inner.typeA.foo
          ))
        )
        test - check(
          "__:^TypeA._",
          Result.Success(Set(
            _.typeB.foo
          ))
        )
        test - check(
          "(__:^inner.TypeA)._",
          Result.Success(Set(
            _.typeA.foo,
            _.typeB.foo
          ))
        )
      }
    }
  }
}
