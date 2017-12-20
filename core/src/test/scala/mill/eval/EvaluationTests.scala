package mill.eval


import mill.util.TestUtil.{Test, test}
import mill.define.{Graph, Target, Task}
import mill.{Module, T}
import mill.discover.Discovered
import mill.discover.Discovered.mapping
import mill.util.{DummyLogger, OSet, TestGraphs, TestUtil}
import utest._
import utest.framework.TestPath

object EvaluationTests extends TestSuite{
  class Checker(mapping: Discovered.Mapping[_])(implicit tp: TestPath) {
    val workspace = ammonite.ops.pwd / 'target / 'workspace / tp.value
    ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))
    // Make sure data is persisted even if we re-create the evaluator each time
    def evaluator = new Evaluator(workspace, mapping.value, DummyLogger)

    def apply(target: Task[_], expValue: Any,
              expEvaled: OSet[Task[_]],
              // How many "other" tasks were evaluated other than those listed above.
              // Pass in -1 to skip the check entirely
              extraEvaled: Int = 0,
              // Perform a second evaluation of the same tasks, and make sure the
              // outputs are the same but nothing was evaluated. Disable this if you
              // are directly evaluating tasks which need to re-evaluate every time
              secondRunNoOp: Boolean = true) = {

      val evaled = evaluator.evaluate(OSet(target))

      val (matchingReturnedEvaled, extra) = evaled.evaluated.indexed.partition(expEvaled.contains)

      assert(
        evaled.values == Seq(expValue),
        matchingReturnedEvaled.toSet == expEvaled.toSet,
        extraEvaled == -1 || extra.length == extraEvaled
      )

      // Second time the value is already cached, so no evaluation needed
      if (secondRunNoOp){
        val evaled2 = evaluator.evaluate(OSet(target))
        val expecteSecondRunEvaluated = OSet()
        assert(
          evaled2.values == evaled.values,
          evaled2.evaluated == expecteSecondRunEvaluated
        )
      }
    }
  }


  val tests = Tests{
    val graphs = new TestGraphs()
    import graphs._
    import TestGraphs._
    'evaluateSingle - {

      'singleton - {
        import singleton._
        val check = new Checker(mapping(singleton))
        // First time the target is evaluated
        check(single, expValue = 0, expEvaled = OSet(single))

        single.counter += 1
        // After incrementing the counter, it forces re-evaluation
        check(single, expValue = 1, expEvaled = OSet(single))
      }
      'pair - {
        import pair._
        val check = new Checker(mapping(pair))
        check(down, expValue = 0, expEvaled = OSet(up, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = OSet(down))

        up.counter += 1
        check(down, expValue = 2, expEvaled = OSet(up, down))
      }
      'anonTriple - {
        import anonTriple._
        val check = new Checker(mapping(anonTriple))
        val middle = down.inputs(0)
        check(down, expValue = 0, expEvaled = OSet(up, middle, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = OSet(middle, down))

        up.counter += 1
        check(down, expValue = 2, expEvaled = OSet(up, middle, down))

        middle.asInstanceOf[TestUtil.Test].counter += 1

        check(down, expValue = 3, expEvaled = OSet(middle, down))
      }
      'diamond - {
        import diamond._
        val check = new Checker(mapping(diamond))
        check(down, expValue = 0, expEvaled = OSet(up, left, right, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = OSet(down))

        up.counter += 1
        // Increment by 2 because up is referenced twice: once by left once by right
        check(down, expValue = 3, expEvaled = OSet(up, left, right, down))

        left.counter += 1
        check(down, expValue = 4, expEvaled = OSet(left, down))

        right.counter += 1
        check(down, expValue = 5, expEvaled = OSet(right, down))
      }
      'anonDiamond - {
        import anonDiamond._
        val check = new Checker(mapping(anonDiamond))
        val left = down.inputs(0).asInstanceOf[TestUtil.Test]
        val right = down.inputs(1).asInstanceOf[TestUtil.Test]
        check(down, expValue = 0, expEvaled = OSet(up, left, right, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = OSet(left, right, down))

        up.counter += 1
        // Increment by 2 because up is referenced twice: once by left once by right
        check(down, expValue = 3, expEvaled = OSet(up, left, right, down))

        left.counter += 1
        check(down, expValue = 4, expEvaled = OSet(left, right, down))

        right.counter += 1
        check(down, expValue = 5, expEvaled = OSet(left, right, down))
      }

      'bigSingleTerminal - {
        import bigSingleTerminal._
        val check = new Checker(mapping(bigSingleTerminal))

        check(j, expValue = 0, expEvaled = OSet(a, b, e, f, i, j), extraEvaled = 22)

        j.counter += 1
        check(j, expValue = 1, expEvaled = OSet(j), extraEvaled = 3)

        i.counter += 1
        // increment value by 2 because `i` is used twice on the way to `j`
        check(j, expValue = 3, expEvaled = OSet(j, i), extraEvaled = 8)

        b.counter += 1
        // increment value by 4 because `b` is used four times on the way to `j`
        check(j, expValue = 7, expEvaled = OSet(b, e, f, i, j), extraEvaled = 20)
      }
    }

    'evaluateMixed - {
      'separateGroups - {
        // Make sure that `left` and `right` are able to recompute separately,
        // even though one depends on the other

        import separateGroups._
        val checker = new Checker(mapping(separateGroups))
        val evaled1 = checker.evaluator.evaluate(OSet(right, left))
        val filtered1 = evaled1.evaluated.filter(_.isInstanceOf[Target[_]])
        assert(filtered1 == OSet(change, left, right))
        val evaled2 = checker.evaluator.evaluate(OSet(right, left))
        val filtered2 = evaled2.evaluated.filter(_.isInstanceOf[Target[_]])
        assert(filtered2 == OSet())
        change.counter += 1
        val evaled3 = checker.evaluator.evaluate(OSet(right, left))
        val filtered3 = evaled3.evaluated.filter(_.isInstanceOf[Target[_]])
        assert(filtered3 == OSet(change, right))


      }
      'triangleTask - {

        import triangleTask._
        val checker = new Checker(mapping(triangleTask))
        checker(right, 3, OSet(left, right), extraEvaled = -1)
        checker(left, 1, OSet(), extraEvaled = -1)

      }
      'multiTerminalGroup - {
        import multiTerminalGroup._

        val checker = new Checker(mapping(multiTerminalGroup))
        checker(right, 1, OSet(right), extraEvaled = -1)
        checker(left, 1, OSet(left), extraEvaled = -1)
      }

      'multiTerminalBoundary - {

        import multiTerminalBoundary._

        val checker = new Checker(mapping(multiTerminalBoundary))
        checker(task2, 4, OSet(right, left), extraEvaled = -1, secondRunNoOp = false)
        checker(task2, 4, OSet(), extraEvaled = -1, secondRunNoOp = false)
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
        object build extends Module{
          var leftCount = 0
          var rightCount = 0
          var middleCount = 0
          def up = T{ test() }
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
        val check = new Checker(mapping(build))
        assert(leftCount == 0, rightCount == 0)
        check(down, expValue = 10101, expEvaled = OSet(up, right, down), extraEvaled = 8)
        assert(leftCount == 1, middleCount == 1, rightCount == 1)

        // If the upstream `up` doesn't change, the entire block of tasks
        // doesn't need to recompute
        check(down, expValue = 10101, expEvaled = OSet())
        assert(leftCount == 1, middleCount == 1, rightCount == 1)

        // But if `up` changes, the entire block of downstream tasks needs to
        // recompute together, including `middle` which doesn't depend on `up`,
        // because tasks have no cached value that can be used. `right`, which
        // is a cached Target, does not recompute
        up.inputs(0).asInstanceOf[Test].counter += 1
        check(down, expValue = 10102, expEvaled = OSet(up, down), extraEvaled = 6)
        assert(leftCount == 2, middleCount == 2, rightCount == 1)

        // Running the tasks themselves results in them being recomputed every
        // single time, even if nothing changes
        check(left, expValue = 2, expEvaled = OSet(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 3, middleCount == 2, rightCount == 1)
        check(left, expValue = 2, expEvaled = OSet(), extraEvaled = 1, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 2, rightCount == 1)

        check(middle, expValue = 100, expEvaled = OSet(), extraEvaled = 2, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 3, rightCount == 1)
        check(middle, expValue = 100, expEvaled = OSet(), extraEvaled = 2, secondRunNoOp = false)
        assert(leftCount == 4, middleCount == 4, rightCount == 1)
      }
    }
  }
}
