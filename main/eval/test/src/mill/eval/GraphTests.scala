package mill.eval

import mill.define.{Task, Target, NamedTask, TargetImpl}
import mill.util.{TestGraphs, TestUtil}
import utest._
import mill.api.Strict.Agg
object GraphTests extends TestSuite {

  val tests = Tests {

    val graphs = new TestGraphs()
    import graphs._
    import TestGraphs._

    test("topoSortedTransitiveTargets") {
      def check(targets: Agg[Task[_]], expected: Agg[Task[_]]) = {
        val result = Graph.topoSorted(Graph.transitiveTargets(targets)).values
        TestUtil.checkTopological(result)
        assert(result == expected)
      }

      test("singleton") - check(
        targets = Agg(singleton.single),
        expected = Agg(singleton.single)
      )
      test("backtickIdentifiers") - check(
        targets = Agg(bactickIdentifiers.`a-down-target`),
        expected = Agg(bactickIdentifiers.`up-target`, bactickIdentifiers.`a-down-target`)
      )
      test("pair") - check(
        targets = Agg(pair.down),
        expected = Agg(pair.up, pair.down)
      )
      test("anonTriple") - check(
        targets = Agg(anonTriple.down),
        expected = Agg(anonTriple.up, anonTriple.down.inputs(0), anonTriple.down)
      )
      test("diamond") - check(
        targets = Agg(diamond.down),
        expected = Agg(diamond.up, diamond.left, diamond.right, diamond.down)
      )
      test("anonDiamond") - check(
        targets = Agg(diamond.down),
        expected = Agg(
          diamond.up,
          diamond.down.inputs(0),
          diamond.down.inputs(1),
          diamond.down
        )
      )
      test("defCachedDiamond") - check(
        targets = Agg(defCachedDiamond.down),
        expected = Agg(
          defCachedDiamond.up.inputs(0),
          defCachedDiamond.up,
          defCachedDiamond.down.inputs(0).inputs(0).inputs(0),
          defCachedDiamond.down.inputs(0).inputs(0),
          defCachedDiamond.down.inputs(0).inputs(1).inputs(0),
          defCachedDiamond.down.inputs(0).inputs(1),
          defCachedDiamond.down.inputs(0),
          defCachedDiamond.down
        )
      )
      test("bigSingleTerminal") {
        val result = Graph.topoSorted(Graph.transitiveTargets(Agg(bigSingleTerminal.j))).values
        TestUtil.checkTopological(result)
        assert(result.size == 28)
      }
    }

    test("groupAroundNamedTargets") {
      def check[T, R <: Target[Int]](base: T)(
          target: T => R,
          important0: Agg[T => Target[_]],
          expected: Agg[(R, Int)]
      ) = {

        val topoSorted = Graph.topoSorted(Graph.transitiveTargets(Agg(target(base))))

        val important = important0.map(_(base))
        val grouped = Graph.groupAroundImportantTargets(topoSorted) {
          case t: TargetImpl[_] if important.contains(t) => t: Target[_]
        }
        val flattened = Agg.from(grouped.values().flatMap(_.items))

        TestUtil.checkTopological(flattened)
        for ((terminal, expectedSize) <- expected) {
          val grouping = grouped.lookupKey(terminal)
          assert(
            grouping.size == expectedSize,
            grouping.flatMap(_.asTarget: Option[Target[_]]).filter(important.contains) == Agg(
              terminal
            )
          )
        }
      }

      test("singleton") - check(singleton)(
        _.single,
        Agg(_.single),
        Agg(singleton.single -> 1)
      )
      test("backtickIdentifiers") - check(bactickIdentifiers)(
        _.`a-down-target`,
        Agg(_.`up-target`, _.`a-down-target`),
        Agg(
          bactickIdentifiers.`up-target` -> 1,
          bactickIdentifiers.`a-down-target` -> 1
        )
      )
      test("pair") - check(pair)(
        _.down,
        Agg(_.up, _.down),
        Agg(pair.up -> 1, pair.down -> 1)
      )
      test("anonTriple") - check(anonTriple)(
        _.down,
        Agg(_.up, _.down),
        Agg(anonTriple.up -> 1, anonTriple.down -> 2)
      )
      test("diamond") - check(diamond)(
        _.down,
        Agg(_.up, _.left, _.right, _.down),
        Agg(
          diamond.up -> 1,
          diamond.left -> 1,
          diamond.right -> 1,
          diamond.down -> 1
        )
      )

      test("defCachedDiamond") - check(defCachedDiamond)(
        _.down,
        Agg(_.up, _.left, _.right, _.down),
        Agg(
          defCachedDiamond.up -> 2,
          defCachedDiamond.left -> 2,
          defCachedDiamond.right -> 2,
          defCachedDiamond.down -> 2
        )
      )

      test("anonDiamond") - check(anonDiamond)(
        _.down,
        Agg(_.down, _.up),
        Agg(
          anonDiamond.up -> 1,
          anonDiamond.down -> 3
        )
      )
      test("bigSingleTerminal") - check(bigSingleTerminal)(
        _.j,
        Agg(_.a, _.b, _.e, _.f, _.i, _.j),
        Agg(
          bigSingleTerminal.a -> 3,
          bigSingleTerminal.b -> 2,
          bigSingleTerminal.e -> 9,
          bigSingleTerminal.i -> 6,
          bigSingleTerminal.f -> 4,
          bigSingleTerminal.j -> 4
        )
      )
    }
    test("multiTerminalGroupCounts") {
      def countGroups(goals: Task[_]*) = {

        val topoSorted = Graph.topoSorted(
          Graph.transitiveTargets(Agg.from(goals))
        )
        val grouped = Graph.groupAroundImportantTargets(topoSorted) {
          case t: NamedTask[Any] => t
          case t if goals.contains(t) => t
        }
        grouped.keyCount
      }

      test("separateGroups") {
        import separateGroups._
        val groupCount = countGroups(right, left)
        assert(groupCount == 3)
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
        // Make sure the following graph ends up as a three groups: one for
        // each cached target, and one for the downstream task we are running
        import multiTerminalBoundary._
        val groupCount = countGroups(task2)
        assert(groupCount == 3)
      }
    }

  }
}
