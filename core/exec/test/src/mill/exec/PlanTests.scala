package mill.exec

import mill.define.{NamedTask, Target, TargetImpl, Task}
import mill.util.TestGraphs
import utest.*

import scala.collection.mutable

object PlanTests extends TestSuite {
  def checkTopological(targets: Seq[Task[?]]) = {
    val seen = mutable.Set.empty[Task[?]]
    for (t <- targets.reverseIterator) {
      seen.add(t)
      for (upstream <- t.inputs) {
        assert(!seen(upstream))
      }
    }
  }

  val tests = Tests {

    import TestGraphs._
    def check(targets: Seq[Task[?]], expected: Seq[Task[?]]) = {
      val result = Plan.topoSorted(Plan.transitiveTargets(targets)).values
      checkTopological(result)
      assert(result == expected)
    }

    test("singleton") - check(
      targets = Seq(singleton.single),
      expected = Seq(singleton.single)
    )

    test("backtickIdentifiers") - check(
      targets = Seq(bactickIdentifiers.`a-down-target`),
      expected = Seq(bactickIdentifiers.`up-target`, bactickIdentifiers.`a-down-target`)
    )

    def countGroups(goals: Task[?]*) = {

      val topoSorted = Plan.topoSorted(
        Plan.transitiveTargets(Seq.from(goals))
      )
      val grouped = Plan.groupAroundImportantTargets(topoSorted) {
        case t: NamedTask[Any] => t
        case t if goals.contains(t) => t
      }
      grouped.keyCount
    }

    test("triangleTask") {
      // Make sure the following graph ends up as a single group, since although
      // `right` depends on `left`, both of them depend on the un-cached `task`
      // which would force them both to re-compute every time `task` changes
      import triangleTask._
      val groupCount = countGroups(right, left)
      assert(groupCount == 2)
    }

    test("multiTerminalGroup") {
      // Make sure the following graph ends up as two groups
      import multiTerminalGroup._
      val groupCount = countGroups(right, left)
      assert(groupCount == 2)
    }

    test("multiTerminalBoundary") {
      // Make sure the following graph ends up as three groups: one for
      // each cached target, and one for the downstream task we are running
      import multiTerminalBoundary._
      val groupCount = countGroups(task2)
      assert(groupCount == 3)
    }
  }
}
