package mill.eval


import mill.util.TestUtil.{Test, test}
import mill.define.{Discover, Graph, Target, Task}
import mill.{Module, T}
import mill.util.{DummyLogger, TestEvaluator, TestGraphs, TestUtil}
import mill.util.Strict.Agg
import utest._
import utest.framework.TestPath

import ammonite.ops._

object EvaluationTests extends TestSuite{
  class Checker[T <: TestUtil.BaseModule](module: T)(implicit tp: TestPath) {
    // Make sure data is persisted even if we re-create the evaluator each time

    def evaluator = new TestEvaluator(module).evaluator

    def apply(target: Task[_], expValue: Any,
              expEvaled: Agg[Task[_]],
              // How many "other" tasks were evaluated other than those listed above.
              // Pass in -1 to skip the check entirely
              extraEvaled: Int = 0,
              // Perform a second evaluation of the same tasks, and make sure the
              // outputs are the same but nothing was evaluated. Disable this if you
              // are directly evaluating tasks which need to re-evaluate every time
              secondRunNoOp: Boolean = true) = {

      val evaled = evaluator.evaluate(Agg(target))

      val (matchingReturnedEvaled, extra) =
        evaled.evaluated.indexed.partition(expEvaled.contains)

      assert(
        evaled.values == Seq(expValue),
        matchingReturnedEvaled.toSet == expEvaled.toSet,
        extraEvaled == -1 || extra.length == extraEvaled
      )

      // Second time the value is already cached, so no evaluation needed
      if (secondRunNoOp){
        val evaled2 = evaluator.evaluate(Agg(target))
        val expecteSecondRunEvaluated = Agg()
        assert(
          evaled2.values == evaled.values,
          evaled2.evaluated == expecteSecondRunEvaluated
        )
      }
    }
  }


  val tests = Tests{
    object graphs extends TestGraphs()
    import graphs._
    import TestGraphs._
    'evaluateSingle - {

      'singleton - {
        import singleton._
        val check = new Checker(singleton)
        // First time the target is evaluated
        check(single, expValue = 0, expEvaled = Agg(single))

        single.counter += 1
        // After incrementing the counter, it forces re-evaluation
        check(single, expValue = 1, expEvaled = Agg(single))
      }
      'pair - {
        import pair._
        val check = new Checker(pair)
        check(down, expValue = 0, expEvaled = Agg(up, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = Agg(down))

        up.counter += 1
        check(down, expValue = 2, expEvaled = Agg(up, down))
      }
      'anonTriple - {
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
      'diamond - {
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
      'anonDiamond - {
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

      'bigSingleTerminal - {
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

    'evaluateMixed - {
      'separateGroups - {
        // Make sure that `left` and `right` are able to recompute separately,
        // even though one depends on the other

        import separateGroups._
        val checker = new Checker(separateGroups)
        val evaled1 = checker.evaluator.evaluate(Agg(right, left))
        val filtered1 = evaled1.evaluated.filter(_.isInstanceOf[Target[_]])
        assert(filtered1 == Agg(change, left, right))
        val evaled2 = checker.evaluator.evaluate(Agg(right, left))
        val filtered2 = evaled2.evaluated.filter(_.isInstanceOf[Target[_]])
        assert(filtered2 == Agg())
        change.counter += 1
        val evaled3 = checker.evaluator.evaluate(Agg(right, left))
        val filtered3 = evaled3.evaluated.filter(_.isInstanceOf[Target[_]])
        assert(filtered3 == Agg(change, right))


      }
      'triangleTask - {

        import triangleTask._
        val checker = new Checker(triangleTask)
        checker(right, 3, Agg(left, right), extraEvaled = -1)
        checker(left, 1, Agg(), extraEvaled = -1)

      }
      'multiTerminalGroup - {
        import multiTerminalGroup._

        val checker = new Checker(multiTerminalGroup)
        checker(right, 1, Agg(right), extraEvaled = -1)
        checker(left, 1, Agg(left), extraEvaled = -1)
      }

      'multiTerminalBoundary - {

        import multiTerminalBoundary._

        val checker = new Checker(multiTerminalBoundary)
        checker(task2, 4, Agg(right, left), extraEvaled = -1, secondRunNoOp = false)
        checker(task2, 4, Agg(), extraEvaled = -1, secondRunNoOp = false)
      }

      'overrideSuperTask - {
        // Make sure you can override targets, call their supers, and have the
        // overriden target be allocated a spot within the overriden/ folder of
        // the main publically-available target
        import canOverrideSuper._

        val checker = new Checker(canOverrideSuper)
        checker(foo, Seq("base", "object"), Agg(foo), extraEvaled = -1)


        val public = ammonite.ops.read(checker.evaluator.outPath / 'foo / "meta.json")
        val overriden = ammonite.ops.read(
          checker.evaluator.outPath / 'foo /
            'overriden / "mill" / "util" / "TestGraphs" / "BaseModule#foo"  / "meta.json"
        )
        assert(
          public.contains("base"),
          public.contains("object"),
          overriden.contains("base"),
          !overriden.contains("object")
        )
      }
      'overrideSuperCommand - {
        // Make sure you can override commands, call their supers, and have the
        // overriden command be allocated a spot within the overriden/ folder of
        // the main publically-available command
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

        val public = ammonite.ops.read(checker.evaluator.outPath / 'cmd / "meta.json")
        val overriden = ammonite.ops.read(
          checker.evaluator.outPath / 'cmd /
          'overriden / "mill" / "util" / "TestGraphs" / "BaseModule#cmd"  / "meta.json"
        )
        assert(
          public.contains("base1"),
          public.contains("object1"),
          overriden.contains("base1"),
          !overriden.contains("object1")
        )
      }

      'tasksAreUncached - {
        // Make sure the tasks `left` and `middle` re-compute every time, while
        // the target `right` does not
        //
        //    ___ left ___
        //   /            \
        // up    middle -- down
        //                /
        //           right
        object build extends TestUtil.BaseModule{
          var leftCount = 0
          var rightCount = 0
          var middleCount = 0
          def up = T{ test.anon() }
          def left = T.task{ leftCount += 1; up() + 1 }
          def middle = T.task{ middleCount += 1; 100 }
          def right = T{ rightCount += 1; 10000 }
          def down = T{ left() + middle() + right() }
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
        check(down, expValue = 10101, expEvaled = Agg(up, right, down), extraEvaled = 8)
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
        check(down, expValue = 10102, expEvaled = Agg(up, down), extraEvaled = 6)
        assert(leftCount == 2, middleCount == 2, rightCount == 1)

        // Running the tasks themselves results in them being recomputed every
        // single time, even if nothing changes
        check(left, expValue = 2, expEvaled = Agg(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 3, middleCount == 2, rightCount == 1)
        check(left, expValue = 2, expEvaled = Agg(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 2, rightCount == 1)

        check(middle, expValue = 100, expEvaled = Agg(), extraEvaled = 2, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 3, rightCount == 1)
        check(middle, expValue = 100, expEvaled = Agg(), extraEvaled = 2, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 4, rightCount == 1)
      }
    }
  }
}
