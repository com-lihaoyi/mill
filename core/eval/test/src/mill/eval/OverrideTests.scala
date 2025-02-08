package mill.eval

import mill.define.{Discover, TargetImpl, Task}
import mill.Module
import mill.testkit.TestBaseModule
import mill.api.Strict.Agg
import utest.*

object OverrideTests extends TestSuite {
  trait BaseModule extends Module {
    def foo = Task { Seq("base") }
    def cmd(i: Int) = Task.Command { Seq("base" + i) }
  }

  object canOverrideSuper extends TestBaseModule with BaseModule {
    override def foo = Task { super.foo() ++ Seq("object") }
    override def cmd(i: Int) = Task.Command { super.cmd(i)() ++ Seq("object" + i) }
    lazy val millDiscover = Discover[this.type]
  }

  object StackableOverrides extends TestBaseModule {
    trait X extends Module {
      def f = Task { 1 }
    }
    trait A extends X {
      override def f = Task { super.f() + 2 }
    }

    trait B extends X {
      override def f = Task { super.f() + 3 }
    }
    object m extends A with B {}
    lazy val millDiscover = Discover[this.type]
  }

  object StackableOverrides2 extends TestBaseModule {
    object A extends Module {
      trait X extends Module {
        def f = Task { 1 }
      }
    }
    object B extends Module {
      trait X extends A.X {
        override def f = Task { super.f() + 2 }
      }
    }

    object m extends B.X {
      override def f = Task { super.f() + 3 }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object StackableOverrides3 extends TestBaseModule {
    object A extends Module {
      trait X extends Module {
        def f = Task { 1 }
      }
    }
    trait X extends A.X {
      override def f = Task { super.f() + 2 }
    }

    object m extends X {
      override def f = Task { super.f() + 3 }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object OptionalOverride extends TestBaseModule {
    trait X extends Module {
      def f = Task { 1 }
    }
    object m extends X {
      override def f = Task { super.f() + 10 }
      def g = Task { super.f() + 100 }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object PrivateTasksInMixedTraits extends TestBaseModule {
    trait M1 extends Module {
      private def foo = Task { "foo-m1" }
      def bar = Task { foo() }
    }
    trait M2 extends Module {
      private def foo = Task { "foo-m2" }
      def baz = Task { foo() }
    }
    object mod extends M1 with M2
    lazy val millDiscover = Discover[this.type]
  }
  val tests = Tests {
    import utest._

    test("overrideSuperTask") {
      // Make sure you can override targets, call their supers, and have the
      // overridden target be allocated a spot within the overridden/ folder of
      // the main publicly-available target
      import canOverrideSuper._

      val checker = new Checker(canOverrideSuper)
      checker(foo, Seq("base", "object"), Agg(foo), extraEvaled = -1)

      val public = os.read(checker.evaluator.outPath / "foo.json")
      val overridden = os.read(
        checker.evaluator.outPath / "foo.super/BaseModule.json"
      )
      assert(
        public.contains("base"),
        public.contains("object"),
        overridden.contains("base"),
        !overridden.contains("object")
      )
    }
    test("overrideSuperCommand") {
      // Make sure you can override commands, call their supers, and have the
      // overridden command be allocated a spot within the super/ folder of
      // the main publicly-available command
      import canOverrideSuper._

      val checker = new Checker(canOverrideSuper)
      val runCmd = cmd(1)
      checker(
        runCmd,
        Seq("base1", "object1"),
        Agg(runCmd),
        extraEvaled = -1,
        secondRunNoOp = false
      )

      val public = os.read(checker.evaluator.outPath / "cmd.json")
      val overridden = os.read(
        checker.evaluator.outPath / "cmd.super/BaseModule.json"
      )
      assert(
        public.contains("base1"),
        public.contains("object1"),
        overridden.contains("base1"),
        !overridden.contains("object1")
      )
    }
    test("stackableOverrides") {
      // Make sure you can override commands, call their supers, and have the
      // overridden command be allocated a spot within the super/ folder of
      // the main publicly-available command
      import StackableOverrides._

      val checker = new Checker(StackableOverrides)
      checker(
        m.f,
        6,
        Agg(m.f),
        extraEvaled = -1
      )

      assert(
        os.read(checker.evaluator.outPath / "m/f.super/X.json")
          .contains(" 1,")
      )
      assert(
        os.read(checker.evaluator.outPath / "m/f.super/A.json")
          .contains(" 3,")
      )
      assert(os.read(checker.evaluator.outPath / "m/f.json").contains(" 6,"))
    }
    test("stackableOverrides2") {
      // When the supers have the same name, qualify them until they are distinct
      import StackableOverrides2._

      val checker = new Checker(StackableOverrides2)
      checker(
        m.f,
        6,
        Agg(m.f),
        extraEvaled = -1
      )

      assert(
        os.read(checker.evaluator.outPath / "m/f.super/A/X.json")
          .contains(" 1,")
      )
      assert(
        os.read(checker.evaluator.outPath / "m/f.super/B/X.json")
          .contains(" 3,")
      )
      assert(os.read(checker.evaluator.outPath / "m/f.json").contains(" 6,"))
    }
    test("stackableOverrides3") {
      // When the supers have the same name, qualify them until they are distinct
      import StackableOverrides3._

      val checker = new Checker(StackableOverrides3)
      checker(
        m.f,
        6,
        Agg(m.f),
        extraEvaled = -1
      )

      assert(
        os.read(checker.evaluator.outPath / "m/f.super/A/X.json")
          .contains(" 1,")
      )
      assert(
        os.read(checker.evaluator.outPath / "m/f.super/X.json")
          .contains(" 3,")
      )
      assert(os.read(checker.evaluator.outPath / "m/f.json").contains(" 6,"))
    }
    test("optionalOverride") {
      // Make sure that when a task is overriden, it always gets put in the same place on
      // disk regardless of whether or not the override is part of the current evaluation
      import OptionalOverride._

      val checker = new Checker(OptionalOverride)
      test {
        checker(m.f, 11, Agg(m.f), extraEvaled = -1)
        assert(
          os.read(checker.evaluator.outPath / "m/f.super/X.json")
            .contains(" 1,")
        )
      }
      test {
        checker(m.g, 101, Agg(), extraEvaled = -1)
        assert(
          os.read(checker.evaluator.outPath / "m/f.super/X.json")
            .contains(" 1,")
        )
      }
    }
    test("privateTasksInMixedTraits") {
      // Make sure we can have private cached targets in different trait with the same name,
      // and caching still works when these traits are mixed together
      import PrivateTasksInMixedTraits._
      val checker = new Checker(PrivateTasksInMixedTraits)
      checker(
        mod.bar,
        "foo-m1",
        Agg(mod.bar),
        extraEvaled = -1
      )
      // If we evaluate to "foo-m1" instead of "foo-m2",
      // we don't properly distinguish between the two private `foo` targets
      checker(
        mod.baz,
        "foo-m2",
        Agg(mod.baz),
        extraEvaled = -1
      )
    }
  }
}
