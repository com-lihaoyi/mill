package mill.define


import mill.discover.Discovered
import mill.eval.Evaluator
import mill.util.{OSet, TestGraphs, TestUtil}
import utest._

object GraphTests extends TestSuite{

  val tests = Tests{


    val graphs = new TestGraphs()
    import graphs._
    import TestGraphs._

    'topoSortedTransitiveTargets - {
      def check(targets: OSet[Task[_]], expected: OSet[Task[_]]) = {
        val result = Graph.topoSorted(Graph.transitiveTargets(targets)).values
        TestUtil.checkTopological(result)
        assert(result == expected)
      }

      'singleton - check(
        targets = OSet(singleton.single),
        expected = OSet(singleton.single)
      )
      'pair - check(
        targets = OSet(pair.down),
        expected = OSet(pair.up, pair.down)
      )
      'anonTriple - check(
        targets = OSet(anonTriple.down),
        expected = OSet(anonTriple.up, anonTriple.down.inputs(0), anonTriple.down)
      )
      'diamond - check(
        targets = OSet(diamond.down),
        expected = OSet(diamond.up, diamond.left, diamond.right, diamond.down)
      )
      'anonDiamond - check(
        targets = OSet(diamond.down),
        expected = OSet(
          diamond.up,
          diamond.down.inputs(0),
          diamond.down.inputs(1),
          diamond.down
        )
      )
      'defCachedDiamond - check(
        targets = OSet(defCachedDiamond.down),
        expected = OSet(
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
      'bigSingleTerminal - {
        val result = Graph.topoSorted(Graph.transitiveTargets(OSet(bigSingleTerminal.j))).values
        TestUtil.checkTopological(result)
        assert(result.size == 28)
      }
    }

    'groupAroundNamedTargets - {
      def check[T, R <: Target[Int]](base: T)
                                    (target: T => R,
                                     important0: OSet[T => Target[_]],
                                     expected: OSet[(R, Int)]) = {

        val topoSorted = Graph.topoSorted(Graph.transitiveTargets(OSet(target(base))))

        val important = important0.map(_ (base))
        val grouped = Graph.groupAroundImportantTargets(topoSorted) {
          case t: Target[_] if important.contains(t) => t
        }
        val flattened = OSet.from(grouped.values().flatMap(_.items))

        TestUtil.checkTopological(flattened)
        for ((terminal, expectedSize) <- expected) {
          val grouping = grouped.lookupKey(terminal)
          assert(
            grouping.size == expectedSize,
            grouping.flatMap(_.asTarget: Option[Target[_]]).filter(important.contains) == OSet(terminal)
          )
        }
      }

      'singleton - check(singleton)(
        _.single,
        OSet(_.single),
        OSet(singleton.single -> 1)
      )
      'pair - check(pair)(
        _.down,
        OSet(_.up, _.down),
        OSet(pair.up -> 1, pair.down -> 1)
      )
      'anonTriple - check(anonTriple)(
        _.down,
        OSet(_.up, _.down),
        OSet(anonTriple.up -> 1, anonTriple.down -> 2)
      )
      'diamond - check(diamond)(
        _.down,
        OSet(_.up, _.left, _.right, _.down),
        OSet(
          diamond.up -> 1,
          diamond.left -> 1,
          diamond.right -> 1,
          diamond.down -> 1
        )
      )

      'defCachedDiamond - check(defCachedDiamond)(
        _.down,
        OSet(_.up, _.left, _.right, _.down),
        OSet(
          defCachedDiamond.up -> 2,
          defCachedDiamond.left -> 2,
          defCachedDiamond.right -> 2,
          defCachedDiamond.down -> 2
        )
      )

      'anonDiamond - check(anonDiamond)(
        _.down,
        OSet(_.down, _.up),
        OSet(
          anonDiamond.up -> 1,
          anonDiamond.down -> 3
        )
      )
      'bigSingleTerminal - check(bigSingleTerminal)(
        _.j,
        OSet(_.a, _.b, _.e, _.f, _.i, _.j),
        OSet(
          bigSingleTerminal.a -> 3,
          bigSingleTerminal.b -> 2,
          bigSingleTerminal.e -> 9,
          bigSingleTerminal.i -> 6,
          bigSingleTerminal.f -> 4,
          bigSingleTerminal.j -> 4
        )
      )
    }
    'multiTerminalGroupCounts - {
      def countGroups[T: Discovered](t: T, goals: Task[_]*) = {
        val labeling = Discovered.mapping(t)
        val topoSorted = Graph.topoSorted(
          Graph.transitiveTargets(OSet.from(goals))
        )
        val grouped = Graph.groupAroundImportantTargets(topoSorted) {
          case t: Target[_] if labeling.contains(t) || goals.contains(t) => t
          case t if goals.contains(t) => t
        }
        grouped.keyCount
      }

      'separateGroups - {
        import separateGroups._
        val groupCount = countGroups(separateGroups, right, left)
        assert(groupCount == 3)
      }

      'triangleTask - {
        // Make sure the following graph ends up as a single group, since although
        // `right` depends on `left`, both of them depend on the un-cached `task`
        // which would force them both to re-compute every time `task` changes
        import triangleTask._
        val groupCount = countGroups(triangleTask, right, left)
        assert(groupCount == 2)
      }


      'multiTerminalGroup - {
        // Make sure the following graph ends up as two groups
        import multiTerminalGroup._
        val groupCount = countGroups(multiTerminalGroup, right, left)
        assert(groupCount == 2)
      }


      'multiTerminalBoundary - {
        // Make sure the following graph ends up as a three groups: one for
        // each cached target, and one for the downstream task we are running
        import multiTerminalBoundary._
        val groupCount = countGroups(multiTerminalBoundary, task2)
        assert(groupCount == 3)
      }
    }


  }
}
