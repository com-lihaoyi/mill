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

    test("topoSortedTransitiveTargets") {
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
      test("pair") - check(
        targets = Seq(pair.down),
        expected = Seq(pair.up, pair.down)
      )
      test("anonTriple") - check(
        targets = Seq(anonTriple.down),
        expected = Seq(anonTriple.up, anonTriple.down.inputs(0), anonTriple.down)
      )
      test("diamond") - check(
        targets = Seq(diamond.down),
        expected = Seq(diamond.up, diamond.left, diamond.right, diamond.down)
      )
      test("anonDiamond") - check(
        targets = Seq(diamond.down),
        expected = Seq(
          diamond.up,
          diamond.down.inputs(0),
          diamond.down.inputs(1),
          diamond.down
        )
      )
    }
    test("groupAroundNamedTargets") {
      def check[T, R <: Target[Int]](base: T)(
          target: T => R,
          important0: Seq[T => Target[?]],
          expected: Seq[(R, Int)]
      ) = {

        val topoSorted = Plan.topoSorted(Plan.transitiveTargets(Seq(target(base))))

        val important = important0.map(_(base))
        val grouped = Plan.groupAroundImportantTargets(topoSorted) {
          case t: TargetImpl[_] if important.contains(t) => t: Target[?]
        }
        val flattened = Seq.from(grouped.values().flatten)

        checkTopological(flattened)
        for ((terminal, expectedSize) <- expected) {
          val grouping = grouped.lookupKey(terminal)
          assert(
            grouping.size == expectedSize,
            grouping.flatMap(_.asTarget: Option[Target[?]]).filter(important.contains) == Seq(
              terminal
            )
          )
        }
      }

      test("singleton") - check(singleton)(
        _.single,
        Seq(_.single),
        Seq(singleton.single -> 1)
      )
      test("backtickIdentifiers") - check(bactickIdentifiers)(
        _.`a-down-target`,
        Seq(_.`up-target`, _.`a-down-target`),
        Seq(
          bactickIdentifiers.`up-target` -> 1,
          bactickIdentifiers.`a-down-target` -> 1
        )
      )
      test("pair") - check(pair)(
        _.down,
        Seq(_.up, _.down),
        Seq(pair.up -> 1, pair.down -> 1)
      )
      test("anonTriple") - check(anonTriple)(
        _.down,
        Seq(_.up, _.down),
        Seq(anonTriple.up -> 1, anonTriple.down -> 2)
      )
      test("diamond") - check(diamond)(
        _.down,
        Seq(_.up, _.left, _.right, _.down),
        Seq(
          diamond.up -> 1,
          diamond.left -> 1,
          diamond.right -> 1,
          diamond.down -> 1
        )
      )

      test("anonDiamond") - check(anonDiamond)(
        _.down,
        Seq(_.down, _.up),
        Seq(
          anonDiamond.up -> 1,
          anonDiamond.down -> 3
        )
      )
    }
    test("multiTerminalGroupCounts") {
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

      test("separateGroups") {
        import separateGroups._
        val groupCount = countGroups(right, left)
        assert(groupCount == 2)
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
}
