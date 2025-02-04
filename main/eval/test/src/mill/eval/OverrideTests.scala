package mill.eval

import mill.util.TestUtil.Test
import mill.define.{Discover, TargetImpl, Task}
import mill.{T, Module}
import mill.util.{TestGraphs, TestUtil}
import mill.testkit.{TestBaseModule, UnitTester}
import mill.api.Strict.Agg
import os.SubPath
import utest.*
import utest.framework.TestPath


object OverrideTests extends TestSuite {
  trait BaseModule extends Module {
    def foo = Task { Seq("base") }
    def cmd(i: Int) = Task.Command { Seq("base" + i) }
  }


 object canOverrideSuper extends TestBaseModule with BaseModule {
    override def foo = Task { super.foo() ++ Seq("object") }
    override def cmd(i: Int) = Task.Command { super.cmd(i)() ++ Seq("object" + i) }
    def millDiscover = Discover[this.type]
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
    def millDiscover = Discover[this.type]
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
    def millDiscover = Discover[this.type]
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
    def millDiscover = Discover[this.type]
  }

  object OptionalOverride extends TestBaseModule {
    trait X extends Module {
      def f = Task { 1 }
    }
    object m extends X {
      override def f = Task { super.f() + 10 }
      def g = Task { super.f() + 100 }
    }
    def millDiscover = Discover[this.type]
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
    def millDiscover = Discover[this.type]
  }
  val tests = Tests {
    object graphs extends TestGraphs()
    import graphs._
    import TestGraphs._
    import utest._
    test("evaluateSingle") {

      test("singleton") {
        import singleton._
        val check = new Checker(singleton)
        // First time the target is evaluated
        check(single, expValue = 0, expEvaled = Agg(single))

        single.counter += 1
        // After incrementing the counter, it forces re-evaluation
        check(single, expValue = 1, expEvaled = Agg(single))
      }
      test("backtickIdentifiers") {
        import graphs.bactickIdentifiers._
        val check = new Checker(bactickIdentifiers)

        check(`a-down-target`, expValue = 0, expEvaled = Agg(`up-target`, `a-down-target`))

        `a-down-target`.counter += 1
        check(`a-down-target`, expValue = 1, expEvaled = Agg(`a-down-target`))

        `up-target`.counter += 1
        check(`a-down-target`, expValue = 2, expEvaled = Agg(`up-target`, `a-down-target`))
      }
      test("pair") {
        import pair._
        val check = new Checker(pair)
        check(down, expValue = 0, expEvaled = Agg(up, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = Agg(down))

        up.counter += 1
        check(down, expValue = 2, expEvaled = Agg(up, down))
      }
      test("anonTriple") {
        import anonTriple._
        val check = new Checker(anonTriple)
        val middle = down.inputs(0)
        check(down, expValue = 0, expEvaled = Agg(up, middle, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = Agg(middle, down))

        up.counter += 1
        check(down, expValue = 2, expEvaled = Agg(up, middle, down))

        middle.asInstanceOf[TestUtil.Test].counter += 1

        check(down, expValue = 3, expEvaled = Agg(middle, down))
      }
      test("diamond") {
        import diamond._
        val check = new Checker(diamond)
        check(down, expValue = 0, expEvaled = Agg(up, left, right, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = Agg(down))

        up.counter += 1
        // Increment by 2 because up is referenced twice: once by left once by right
        check(down, expValue = 3, expEvaled = Agg(up, left, right, down))

        left.counter += 1
        check(down, expValue = 4, expEvaled = Agg(left, down))

        right.counter += 1
        check(down, expValue = 5, expEvaled = Agg(right, down))
      }
      test("anonDiamond") {
        import anonDiamond._
        val check = new Checker(anonDiamond)
        val left = down.inputs(0).asInstanceOf[TestUtil.Test]
        val right = down.inputs(1).asInstanceOf[TestUtil.Test]
        check(down, expValue = 0, expEvaled = Agg(up, left, right, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = Agg(left, right, down))

        up.counter += 1
        // Increment by 2 because up is referenced twice: once by left once by right
        check(down, expValue = 3, expEvaled = Agg(up, left, right, down))

        left.counter += 1
        check(down, expValue = 4, expEvaled = Agg(left, right, down))

        right.counter += 1
        check(down, expValue = 5, expEvaled = Agg(left, right, down))
      }

      test("bigSingleTerminal") {
        import bigSingleTerminal._
        val check = new Checker(bigSingleTerminal)

        check(j, expValue = 0, expEvaled = Agg(a, b, e, f, i, j), extraEvaled = 22)

        j.counter += 1
        check(j, expValue = 1, expEvaled = Agg(j), extraEvaled = 3)

        i.counter += 1
        // increment value by 2 because `i` is used twice on the way to `j`
        check(j, expValue = 3, expEvaled = Agg(j, i), extraEvaled = 8)

        b.counter += 1
        // increment value by 4 because `b` is used four times on the way to `j`
        check(j, expValue = 7, expEvaled = Agg(b, e, f, i, j), extraEvaled = 20)
      }
    }

    test("evaluateMixed") {
      test("separateGroups") {
        // Make sure that `left` and `right` are able to recompute separately,
        // even though one depends on the other

        import separateGroups._
        val checker = new Checker(separateGroups)
        val evaled1 = checker.evaluator.evaluate(Agg(right, left))
        val filtered1 = evaled1.evaluated.filter(_.isInstanceOf[TargetImpl[_]])
        assert(filtered1.toSeq.sortBy(_.toString) == Seq(change, left, right).sortBy(_.toString))
        val evaled2 = checker.evaluator.evaluate(Agg(right, left))
        val filtered2 = evaled2.evaluated.filter(_.isInstanceOf[TargetImpl[_]])
        assert(filtered2 == Agg())
        change.counter += 1
        val evaled3 = checker.evaluator.evaluate(Agg(right, left))
        val filtered3 = evaled3.evaluated.filter(_.isInstanceOf[TargetImpl[_]])
        assert(filtered3 == Agg(change, right))

      }
      test("triangleTask") {

        import triangleTask._
        val checker = new Checker(triangleTask)
        checker(right, 3, Agg(left, right), extraEvaled = -1)
        checker(left, 1, Agg(), extraEvaled = -1)

      }
      test("multiTerminalGroup") {
        import multiTerminalGroup._

        val checker = new Checker(multiTerminalGroup)
        checker(right, 1, Agg(right), extraEvaled = -1)
        checker(left, 1, Agg(left), extraEvaled = -1)
      }

      test("multiTerminalBoundary") {

        import multiTerminalBoundary._

        val checker = new Checker(multiTerminalBoundary)
        checker(task2, 4, Agg(right, left), extraEvaled = -1, secondRunNoOp = false)
        checker(task2, 4, Agg(), extraEvaled = -1, secondRunNoOp = false)
      }

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
      test{
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
