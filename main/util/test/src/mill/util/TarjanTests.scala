package mill.util

import utest._

object TarjanTests extends TestSuite {
  def check(input: Seq[Seq[Int]], expected: Seq[Seq[Int]]) = {
    val result = Tarjans(input).map(_.sorted)
    val sortedExpected = expected.map(_.sorted)
    assert(result == sortedExpected)
  }
  val tests = Tests {
    //
    "empty" - check(Seq(), Seq())

    // (0)
    "singleton" - check(Seq(Seq()), Seq(Seq(0)))

    // (0)-.
    //  ^._/
    "selfCycle" - check(Seq(Seq(0)), Seq(Seq(0)))

    // (0) <-> (1)
    "simpleCycle" - check(Seq(Seq(1), Seq(0)), Seq(Seq(1, 0)))

    // (0)  (1)  (2)
    "multipleSingletons" - check(
      Seq(Seq(), Seq(), Seq()),
      Seq(Seq(0), Seq(1), Seq(2))
    )

    // (0) -> (1) -> (2)
    "straightLineNoCycles" - check(
      Seq(Seq(1), Seq(2), Seq()),
      Seq(Seq(2), Seq(1), Seq(0))
    )

    // (0) <- (1) <- (2)
    "straightLineNoCyclesReversed" - check(
      Seq(Seq(), Seq(0), Seq(1)),
      Seq(Seq(0), Seq(1), Seq(2))
    )

    // (0) <-> (1)   (2) -> (3) -> (4)
    //                ^.____________/
    "independentSimpleCycles" - check(
      Seq(Seq(1), Seq(0), Seq(3), Seq(4), Seq(2)),
      Seq(Seq(1, 0), Seq(4, 3, 2))
    )

    //           ___________________
    //          v                   \
    // (0) <-> (1)   (2) -> (3) -> (4)
    //                ^.____________/
    "independentLinkedCycles" - check(
      Seq(Seq(1), Seq(0), Seq(3), Seq(4), Seq(2, 1)),
      Seq(Seq(1, 0), Seq(4, 3, 2))
    )
    //   _____________
    //  /             v
    // (0) <-> (1)   (2) -> (3) -> (4)
    //                ^.____________/
    "independentLinkedCycles2" - check(
      Seq(Seq(1, 2), Seq(0), Seq(3), Seq(4), Seq(2)),
      Seq(Seq(4, 3, 2), Seq(1, 0))
    )

    //   _____________
    //  /             v
    // (0) <-> (1)   (2) -> (3) -> (4)
    //          ^.    ^.____________/
    //            \________________/
    "combinedCycles" - check(
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
    "combinedCycles" - check(
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

  }
}
