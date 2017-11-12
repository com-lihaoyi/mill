package mill


import mill.TestUtil.{Test, test}
import mill.define.{Target, Task}
import mill.define.Task.Cacher
import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.OSet
import utest._
import utest.framework.TestPath

object EvaluationTests extends TestSuite{
  class Checker[T: Discovered](base: T)(implicit tp: TestPath) {
    val workspace = ammonite.ops.pwd / 'target / 'workspace / tp.value
    ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))
    // Make sure data is persisted even if we re-create the evaluator each time
    def evaluator = new Evaluator(workspace, Discovered.mapping(base))

    def apply(target: Task[_], expValue: Any,
              expEvaled: OSet[Task[_]],
              // How many "other" tasks were evaluated other than those listed above.
              // Pass in -1 to skip the check entirely
              extraEvaled: Int = 0,
              // Perform a second evaluation of the same tasks, and make sure the
              // outputs are the same but nothing was evaluated. Disable this if you
              // are directly evaluating tasks which need to re-evaluate every time
              secondRunNoOp: Boolean = true) = {

      val Evaluator.Results(returnedValues, returnedEvaluated) = evaluator.evaluate(OSet(target))

      val (matchingReturnedEvaled, extra) = returnedEvaluated.items.partition(expEvaled.contains)

      assert(
        returnedValues == Seq(expValue),
        matchingReturnedEvaled.toSet == expEvaled.toSet,
        extraEvaled == -1 || extra.length == extraEvaled
      )

      // Second time the value is already cached, so no evaluation needed
      if (secondRunNoOp){
        val Evaluator.Results(returnedValues2, returnedEvaluated2) = evaluator.evaluate(OSet(target))
        val expecteSecondRunEvaluated = OSet()
        assert(
          returnedValues2 == returnedValues,
          returnedEvaluated2 == expecteSecondRunEvaluated
        )
      }
    }
  }
  def countGroups[T: Discovered](t: T, terminals: Task[_]*) = {
    val labeling = Discovered.mapping(t)
    val topoSorted = Evaluator.topoSortedTransitiveTargets(OSet.from(terminals))
    val grouped = Evaluator.groupAroundNamedTargets(topoSorted, labeling)
    grouped.keyCount
  }

  val tests = Tests{
    val graphs = new TestGraphs()
    import graphs._
    'evaluateSingle - {


      'singleton - {
        import singleton._
        val check = new Checker(singleton)
        // First time the target is evaluated
        check(single, expValue = 0, expEvaled = OSet(single))

        single.counter += 1
        // After incrementing the counter, it forces re-evaluation
        check(single, expValue = 1, expEvaled = OSet(single))
      }
      'pair - {
        import pair._
        val check = new Checker(pair)
        check(down, expValue = 0, expEvaled = OSet(up, down))

        down.counter += 1
        check(down, expValue = 1, expEvaled = OSet(down))

        up.counter += 1
        check(down, expValue = 2, expEvaled = OSet(up, down))
      }
      'anonTriple - {
        import anonTriple._
        val check = new Checker(anonTriple)
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
        val check = new Checker(diamond)
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
        val check = new Checker(anonDiamond)
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
        val check = new Checker(bigSingleTerminal)

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
      'triangleTask - {
        // Make sure the following graph ends up as a single group, since although
        // `right` depends on `left`, both of them depend on the un-cached `task`
        // which would force them both to re-compute every time `task` changes
        //
        //      _ left _
        //     /        \
        // task -------- right
        object taskTriangle extends Cacher{
          val task = T.task{ 1 }
          def left = T{ task() }
          def right = T{ task() + left() + 1 }
        }

        val groupCount = countGroups(taskTriangle, taskTriangle.right, taskTriangle.left)
        assert(groupCount == 1)
        val checker = new Checker(taskTriangle)
        checker(taskTriangle.right, 3, OSet(taskTriangle.right), extraEvaled = -1)
        checker(taskTriangle.left, 1, OSet(taskTriangle.left), extraEvaled = -1)

      }
      'multiTerminalGroup - {
        // Make sure the following graph ends up as a single group
        //
        //      _ left
        //     /
        // task -------- right
        object taskTriangle extends Cacher{
          val task = T.task{ 1 }
          def left = T{ task() }
          def right = T{ task() }
        }
        val groupCount = countGroups(taskTriangle, taskTriangle.right, taskTriangle.left)
        assert(groupCount == 1)

        val checker = new Checker(taskTriangle)
        checker(taskTriangle.right, 1, OSet(taskTriangle.right), extraEvaled = -1)
        checker(taskTriangle.left, 1, OSet(taskTriangle.left), extraEvaled = -1)
      }

      'multiTerminalBoundary - {
        // Make sure the following graph ends up as a single group
        //
        //       _ left _____________
        //      /        \           \
        // task1 -------- right ----- task2
        object multiTerminalBoundary extends Cacher{
          val task1 = T.task{ 1 }
          def left = T{ task1() }
          def right = T{ task1() + left() + 1 }
          val task2 = T.task{ left() + right() }
        }
        import multiTerminalBoundary._
        val groupCount = countGroups(multiTerminalBoundary, task2)
        assert(groupCount == 2)


        val checker = new Checker(multiTerminalBoundary)
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
        object taskDiamond extends Cacher{
          var leftCount = 0
          var rightCount = 0
          var middleCount = 0
          def up = T{ test() }
          def left = T.task{ leftCount += 1; up() + 1 }
          def middle = T.task{ middleCount += 1; 100 }
          def right = T{ rightCount += 1; 10000 }
          def down = T{ left() + middle() + right() }
        }

        import taskDiamond._

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
        val check = new Checker(taskDiamond)
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
