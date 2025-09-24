package mill.eval

import mill.util.TestUtil.Test
import mill.define.{TargetImpl, Task}
import mill.util.{TestGraphs, TestUtil}
import mill.testkit.{UnitTester, TestBaseModule}
import mill.api.Strict.Agg
import utest._

object EvaluationTestsThreads1 extends EvaluationTests(threadCount = Some(1))
object EvaluationTestsThreads4 extends EvaluationTests(threadCount = Some(3))
object EvaluationTestsThreadsNative extends EvaluationTests(threadCount = None)

class EvaluationTests(threadCount: Option[Int]) extends TestSuite {

  class Checker[T <: mill.testkit.TestBaseModule](module: T) {
    // Make sure data is persisted even if we re-create the evaluator each time

    val evaluator = UnitTester(module, null, threads = threadCount).evaluator

    def apply(
        target: Task[_],
        expValue: Any,
        expEvaled: Agg[Task[_]],
        // How many "other" tasks were evaluated other than those listed above.
        // Pass in -1 to skip the check entirely
        extraEvaled: Int = 0,
        // Perform a second evaluation of the same tasks, and make sure the
        // outputs are the same but nothing was evaluated. Disable this if you
        // are directly evaluating tasks which need to re-evaluate every time
        secondRunNoOp: Boolean = true
    ) = {

      val evaled = evaluator.evaluate(Agg(target))

      val (matchingReturnedEvaled, extra) =
        evaled.evaluated.indexed.partition(expEvaled.contains)

      assert(
        evaled.values.map(_.value) == Seq(expValue),
        matchingReturnedEvaled.toSet == expEvaled.toSet,
        extraEvaled == -1 || extra.length == extraEvaled
      )

      // Second time the value is already cached, so no evaluation needed
      if (secondRunNoOp) {
        val evaled2 = evaluator.evaluate(Agg(target))
        val expectedSecondRunEvaluated = Agg()
        assert(
          evaled2.values.map(_.value) == evaled.values.map(_.value),
          evaled2.evaluated == expectedSecondRunEvaluated
        )
      }
    }
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
      test("nullTasks") {
        import nullTasks._
        val checker = new Checker(nullTasks)
        checker(nullTarget1, null, Agg(nullTarget1), extraEvaled = -1)
        checker(nullTarget1, null, Agg(), extraEvaled = -1)
        checker(nullTarget2, null, Agg(nullTarget2), extraEvaled = -1)
        checker(nullTarget2, null, Agg(), extraEvaled = -1)
        checker(nullTarget3, null, Agg(nullTarget3), extraEvaled = -1)
        checker(nullTarget3, null, Agg(), extraEvaled = -1)
        checker(nullTarget4, null, Agg(nullTarget4), extraEvaled = -1)
        checker(nullTarget4, null, Agg(), extraEvaled = -1)

        val nc1 = nullCommand1()
        val nc2 = nullCommand2()
        val nc3 = nullCommand3()
        val nc4 = nullCommand4()

        checker(nc1, null, Agg(nc1), extraEvaled = -1, secondRunNoOp = false)
        checker(nc1, null, Agg(nc1), extraEvaled = -1, secondRunNoOp = false)
        checker(nc2, null, Agg(nc2), extraEvaled = -1, secondRunNoOp = false)
        checker(nc2, null, Agg(nc2), extraEvaled = -1, secondRunNoOp = false)
        checker(nc3, null, Agg(nc3), extraEvaled = -1, secondRunNoOp = false)
        checker(nc3, null, Agg(nc3), extraEvaled = -1, secondRunNoOp = false)
        checker(nc4, null, Agg(nc4), extraEvaled = -1, secondRunNoOp = false)
        checker(nc4, null, Agg(nc4), extraEvaled = -1, secondRunNoOp = false)
      }

      test("tasksAreUncached") {
        // Make sure the tasks `left` and `middle` re-compute every time, while
        // the target `right` does not
        //
        //    ___ left ___
        //   /            \
        // up    middle -- down
        //                /
        //           right
        object build extends TestBaseModule {
          var leftCount = 0
          var rightCount = 0
          var middleCount = 0
          def up = Task { TestUtil.test.anon() }
          def left = Task.Anon { leftCount += 1; up() + 1 }
          def middle = Task.Anon { middleCount += 1; 100 }
          def right = Task { rightCount += 1; 10000 }
          def down = Task { left() + middle() + right() }
        }

        import build._

        // Ensure task objects themselves are not cached, and recomputed each time
        assert(
          up eq up,
          left ne left,
          middle ne middle,
          right eq right,
          down eq down
        )

        // During the first evaluation, they get computed normally like any
        // cached target
        val check = new Checker(build)
        assert(leftCount == 0, rightCount == 0)
        check(down, expValue = 10101, expEvaled = Agg(up, right, down), extraEvaled = 5)
        assert(leftCount == 1, middleCount == 1, rightCount == 1)

        // If the upstream `up` doesn't change, the entire block of tasks
        // doesn't need to recompute
        check(down, expValue = 10101, expEvaled = Agg())
        assert(leftCount == 1, middleCount == 1, rightCount == 1)

        // But if `up` changes, the entire block of downstream tasks needs to
        // recompute together, including `middle` which doesn't depend on `up`,
        // because tasks have no cached value that can be used. `right`, which
        // is a cached Target, does not recompute
        up.inputs(0).asInstanceOf[Test].counter += 1
        check(down, expValue = 10102, expEvaled = Agg(up, down), extraEvaled = 4)
        assert(leftCount == 2, middleCount == 2, rightCount == 1)

        // Running the tasks themselves results in them being recomputed every
        // single time, even if nothing changes
        check(left, expValue = 2, expEvaled = Agg(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 3, middleCount == 2, rightCount == 1)
        check(left, expValue = 2, expEvaled = Agg(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 2, rightCount == 1)

        check(middle, expValue = 100, expEvaled = Agg(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 3, rightCount == 1)
        check(middle, expValue = 100, expEvaled = Agg(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 4, rightCount == 1)
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
