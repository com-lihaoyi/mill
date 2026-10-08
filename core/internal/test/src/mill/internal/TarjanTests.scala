package mill.internal

import utest.*

object TarjanTests extends TestSuite {
  def check(input: Seq[Seq[Int]], expected: Seq[Seq[Int]]) = {
    val result = Tarjans(input.toArray.map(_.toArray)).map(_.sorted).toSeq.map(_.toSeq)
    val sortedExpected = expected.map(_.sorted)
    assert(result == sortedExpected)
  }
  val tests = Tests {
    //
    test("empty") - check(Seq(), Seq())

    // (0)
    test("singleton") - check(Seq(Seq()), Seq(Seq(0)))

    // (0)-.
    //  ^._/
    test("selfCycle") - check(Seq(Seq(0)), Seq(Seq(0)))

    // (0) <-> (1)
    test("simpleCycle") - check(Seq(Seq(1), Seq(0)), Seq(Seq(1, 0)))

    // (0)  (1)  (2)
    test("multipleSingletons") - check(
      Seq(Seq(), Seq(), Seq()),
      Seq(Seq(0), Seq(1), Seq(2))
    )

    // (0) -> (1) -> (2)
    test("straightLineNoCycles") - check(
      Seq(Seq(1), Seq(2), Seq()),
      Seq(Seq(2), Seq(1), Seq(0))
    )

    // (0) <- (1) <- (2)
    test("straightLineNoCyclesReversed") - check(
      Seq(Seq(), Seq(0), Seq(1)),
      Seq(Seq(0), Seq(1), Seq(2))
    )

    // (0) <-> (1)   (2) -> (3) -> (4)
    //                ^.____________/
    test("independentSimpleCycles") - check(
      Seq(Seq(1), Seq(0), Seq(3), Seq(4), Seq(2)),
      Seq(Seq(1, 0), Seq(4, 3, 2))
    )

    //           ___________________
    //          v                   \
    // (0) <-> (1)   (2) -> (3) -> (4)
    //                ^.____________/
    test("independentLinkedCycles") - check(
      Seq(Seq(1), Seq(0), Seq(3), Seq(4), Seq(2, 1)),
      Seq(Seq(1, 0), Seq(4, 3, 2))
    )
    //   _____________
    //  /             v
    // (0) <-> (1)   (2) -> (3) -> (4)
    //                ^.____________/
    test("independentLinkedCycles2") - check(
      Seq(Seq(1, 2), Seq(0), Seq(3), Seq(4), Seq(2)),
      Seq(Seq(4, 3, 2), Seq(1, 0))
    )

    //   _____________
    //  /             v
    // (0) <-> (1)   (2) -> (3) -> (4)
    //          ^.    ^.____________/
    //            \________________/
    test("combinedCycles") - check(
      Seq(Seq(1, 2), Seq(0), Seq(3), Seq(4), Seq(2, 1)),
      Seq(Seq(4, 3, 2, 1, 0))
    )
    //
    // (0) <-> (1) <- (2) <- (3) <-> (4) <- (5)
    //  ^.____________/      /              /
    //                      /              /
    //        (6) <- (7) <-/        (8) <-'
    //       /            /
    //      v            /
    //     (9) <--------'
    test("combinedCycles") - check(
      Seq(
        Seq(1),
        Seq(0),
        Seq(0, 1),
        Seq(2, 4, 7, 9),
        Seq(3),
        Seq(4, 8),
        Seq(9),
        Seq(6),
        Seq(),
        Seq()
      ),
      Seq(Seq(0, 1), Seq(2), Seq(9), Seq(6), Seq(7), Seq(3, 4), Seq(8), Seq(5))
    )

    // Deep linear chain that would overflow recursive DFS
    test("deepChain") {
      val n = 100000
      val graph = (0 until n).map(i => if (i < n - 1) Seq(i + 1) else Seq.empty)
      val result = Tarjans(graph.toArray.map(_.toArray)).map(_.sorted).toSeq.map(_.toSeq)
      // Each node is its own SCC, in reverse topological order
      assert(result.length == n)
      assert(result.head == Seq(n - 1))
      assert(result.last == Seq(0))
    }

    // Dense graph where every node connects to every other (would create deep recursion)
    test("denseGraph") {
      val n = 10000
      val graph = (0 until n).map(i => (0 until n).filter(_ != i))
      val result = Tarjans(graph.toArray.map(_.toArray)).map(_.sorted).toSeq.map(_.toSeq)
      // All nodes form one big SCC
      assert(result.length == 1)
      assert(result.head == (0 until n).toSeq)
    }
  }
}
