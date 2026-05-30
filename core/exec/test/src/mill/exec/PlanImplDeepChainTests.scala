package mill.exec

import mill.api.Task
import mill.api.Result
import mill.api.TestGraphs
import utest.*

/**
 * Regression tests for the conversion of [[PlanImpl.transitiveNodes]] and
 * [[PlanImpl.groupAroundImportantTasks]] from recursion to iterative
 * worklist/stack traversal.
 *
 * `Tarjans` was deliberately made iterative to survive deep (100k+) linear
 * graphs, but `transitiveNodes`/`groupAroundImportantTasks` ran a recursive
 * pre-pass whose depth equalled the longest input chain, overflowing the stack
 * one frame earlier. These tests assert that:
 *
 *  - a 100000-deep linear `Task.Anon` chain no longer overflows, and
 *  - the traversal/result ORDER on small graphs is exactly what the previous
 *    recursive implementation produced (downstream determinism depends on it).
 */
object PlanImplDeepChainTests extends TestSuite {

  /** A no-op `Task.Anon` with the given explicit inputs; its body is never run. */
  private def anon(inputs: Seq[Task[?]]): Task.Anon[Int] =
    new Task.Anon[Int](
      inputs,
      (_, _) => Result.Success(0),
      sourcecode.Enclosing("PlanImplDeepChainTests")
    )

  /** Builds a linear chain `leaf <- ... <- tip` of `depth` anonymous tasks. */
  private def linearChain(depth: Int): Task[?] = {
    var current: Task[?] = anon(Nil)
    var i = 1
    while (i < depth) {
      current = anon(Seq(current))
      i += 1
    }
    current
  }

  val tests = Tests {

    val deep = 100000

    // Before the fix these overflowed the stack inside the recursive `rec`
    // helpers (depth == chain length), well before the iterative `Tarjans`.
    test("transitiveTasksDeepChainNoStackOverflow") {
      val tip = linearChain(deep)
      val transitive = PlanImpl.transitiveTasks(Seq(tip))
      assert(transitive.length == deep)
      // First-seen pre-order: the tip is visited first.
      assert(transitive.head == tip)
    }

    test("transitiveNodesDeepChainNoStackOverflow") {
      // Drive `transitiveNodes` directly over an integer chain so the traversal,
      // not `Task` construction, is what is exercised at depth.
      val n = deep
      val children: Int => Seq[Int] = i => if (i < n - 1) Seq(i + 1) else Nil
      val nodes = PlanImpl.transitiveNodes(Seq(0))(children)
      assert(nodes.length == n)
      assert(nodes.head == 0)
      assert(nodes.last == n - 1)
    }

    test("planDeepChainNoStackOverflow") {
      val tip = linearChain(deep)
      // `plan` runs transitiveTasks -> topoSorted(Tarjans) -> groupAroundImportantTasks.
      // The deep recursion previously blew up in the first/last of these before
      // Tarjans (already iterative) was reached.
      val plan = PlanImpl.plan(Seq(tip))
      // Every task in a pure-anonymous chain becomes its own group terminal only
      // for the goal; non-goal anon tasks are not cut points, so there is a
      // single group keyed by the goal containing the whole chain.
      val allTasks = plan.sortedGroups.values().flatten.toSeq
      assert(allTasks.length == deep)
    }

    // Order-preservation: `transitiveNodes` must still yield first-seen DFS
    // pre-order. These are the exact orders the previous recursive code emitted.
    test("transitiveNodesOrderingPreserved") {

      // Hand-built small graph with shared sub-nodes, exercising the
      // "already-seen child is skipped" path:
      //
      //        a
      //       / \
      //      b   c
      //     / \ /
      //    d   e
      //
      // Recursive pre-order from `a`, inputs walked left-to-right:
      //   a, b, d, e, c   (c's child `e` already seen -> skipped)
      val children = Map(
        'a' -> Seq('b', 'c'),
        'b' -> Seq('d', 'e'),
        'c' -> Seq('e'),
        'd' -> Seq.empty[Char],
        'e' -> Seq.empty[Char]
      )
      val order = PlanImpl.transitiveNodes(Seq('a'))(children)
      assert(order == IndexedSeq('a', 'b', 'd', 'e', 'c'))

      // Multiple source nodes: each is fully DFS'd in turn, left-to-right,
      // with cross-source duplicates skipped on first sight.
      //   sources [a, c] -> a, b, d, e, c
      val multi = PlanImpl.transitiveNodes(Seq('a', 'c'))(children)
      assert(multi == IndexedSeq('a', 'b', 'd', 'e', 'c'))
    }

    // The same ordering guarantee, but through the real `Task` graphs used by
    // `PlanTests`, so we are pinned to the established `transitiveTasks` output.
    test("transitiveTasksOrderingPreservedOnTestGraphs") {
      import TestGraphs.*

      // diamond.down: down, left, up, right  (right's child `up` already seen)
      assert(
        PlanImpl.transitiveTasks(Seq(diamond.down)) ==
          IndexedSeq(diamond.down, diamond.left, diamond.up, diamond.right)
      )

      // anonDiamond.down with anonymous left/right inputs, same shape.
      val ad = anonDiamond.down
      assert(
        PlanImpl.transitiveTasks(Seq(ad)) ==
          IndexedSeq(ad, ad.inputs(0), anonDiamond.up, ad.inputs(1))
      )

      // anonTriple: down, anon, up
      assert(
        PlanImpl.transitiveTasks(Seq(anonTriple.down)) ==
          IndexedSeq(anonTriple.down, anonTriple.down.inputs(0), anonTriple.up)
      )
    }
  }
}
