package mill.internal

import utest.{TestSuite, Tests, test}

/**
 * Tests that [[SpanningForest]] emits a deterministic, ascending child order at
 * every level of the forest.
 *
 * The fixed bug laundered the sorted roots through an unordered `mutable.Map`
 * before seeding the forest's `LinkedHashMap`, discarding the sort and emitting
 * the roots in HashMap iteration order. A handful of hand-picked inputs can't
 * establish a non-determinism/ordering property — HashMap order coincides with
 * sorted order for many inputs, and which inputs differ depends on the JVM's hash
 * layout. So the invariant is fuzzed over many random DAGs: at every level the
 * child keys must be in ascending order (a single deterministic order, regardless
 * of hash layout), and recomputing must produce byte-identical structure + order.
 */
object SpanningForestOrderTests extends TestSuite {

  /** The ordered `(key -> children)` shape of a forest, capturing iteration order. */
  private def shape(node: SpanningForest.Node): Seq[(Int, Any)] =
    node.values.toSeq.map { case (k, v) => (k, shape(v)) }

  /** Assert child keys are in ascending order at every level of the forest. */
  private def assertSortedAtEveryLevel(node: SpanningForest.Node): Unit = {
    val keys = node.values.keys.toSeq
    assert(keys == keys.sorted)
    node.values.valuesIterator.foreach(assertSortedAtEveryLevel)
  }

  /** A random acyclic edge set over `n` vertices (edges only point to higher indices). */
  private def randomDag(rng: scala.util.Random, n: Int): Array[Array[Int]] = {
    val edges = Array.fill(n)(Array.empty[Int])
    for (i <- 0 until n - 1) {
      val fanout = rng.nextInt(4)
      edges(i) = Seq.fill(fanout)(i + 1 + rng.nextInt(n - i - 1)).distinct.sorted.toArray
    }
    edges
  }

  val tests = Tests {

    // One concrete, readable example: scattered roots, each heading a small subtree.
    test("example") {
      val edges = Array.fill(32)(Array.empty[Int])
      edges(1) = Array(31)
      edges(30) = Array(13)
      val important = Set(1, 3, 8, 9, 12, 17, 25, 30, 31, 13)
      val forest = SpanningForest.applyInferRoots(edges, important)
      // 31 and 13 have incoming edges from roots, so they are children, not top-level.
      assert(forest.values.keys.toSeq == Seq(1, 3, 8, 9, 12, 17, 25, 30))
      assert(forest.values(1).values.keys.toSeq == Seq(31))
      assert(forest.values(30).values.keys.toSeq == Seq(13))
    }

    // Fuzz: for arbitrary DAGs + important subsets, the inferred-root forest must be
    // sorted at every level, equal to the inferred roots, and deterministic.
    test("fuzzInferredRootsSortedAtEveryLevel") {
      val rng = new scala.util.Random(0x5eed)
      var multiRootCases = 0
      for (_ <- 0 until 3000) {
        val n = 1 + rng.nextInt(40)
        val edges = randomDag(rng, n)
        val important = (0 until n).filter(_ => rng.nextDouble() < 0.6).toSet
        if (important.nonEmpty) {
          val forest = SpanningForest.applyInferRoots(edges, important)

          assertSortedAtEveryLevel(forest)

          val destinations = important.flatMap(edges(_))
          val expectedRoots = important.filter(!destinations.contains(_)).toSeq.sorted
          assert(forest.values.keys.toSeq == expectedRoots)
          if (expectedRoots.size >= 3) multiRootCases += 1

          // Pure, deterministic function: recomputing yields identical structure AND order.
          assert(shape(SpanningForest.applyInferRoots(edges, important)) == shape(forest))
        }
      }
      // Guard against the generator degenerating to trivial single-root forests where
      // ordering can't distinguish the bug.
      assert(multiRootCases > 300)
    }

    // `applyWithRoots` honours the *supplied* root order for the top level (it is not
    // re-sorted), so a caller-chosen ordering must round-trip exactly.
    test("fuzzExplicitRootsPreserveSuppliedOrder") {
      val rng = new scala.util.Random(0xb0a7)
      for (_ <- 0 until 1000) {
        val n = 1 + rng.nextInt(30)
        val edges = randomDag(rng, n)
        val rootsOrdered = rng.shuffle((0 until n).filter(_ => rng.nextBoolean()).toVector)
        val important = rootsOrdered.toSet
        if (important.nonEmpty) {
          val forest = SpanningForest.applyWithRoots(edges, rootsOrdered, important)
          assert(forest.values.keys.toSeq == rootsOrdered.filter(important))
        }
      }
    }

    // The JSON rendering reads the same `LinkedHashMap`, so object key order must mirror
    // the forest's order at every level.
    test("fuzzJsonKeyOrderMatchesForest") {
      val rng = new scala.util.Random(0x305)
      // The JSON object key order must mirror the forest's LinkedHashMap order at every level.
      def assertJsonMatches(node: SpanningForest.Node, json: ujson.Value): Unit = {
        assert(json.obj.keys.toSeq.map(_.toInt) == node.values.keys.toSeq)
        for ((k, child) <- node.values) assertJsonMatches(child, json.obj(k.toString))
      }
      for (_ <- 0 until 1000) {
        val n = 1 + rng.nextInt(30)
        val edges = randomDag(rng, n)
        val important = (0 until n).filter(_ => rng.nextBoolean()).toSet
        if (important.nonEmpty) {
          val forest = SpanningForest.applyInferRoots(edges, important)
          val json = SpanningForest.writeJson(edges, important, _.toString)
          assertJsonMatches(forest, json)
        }
      }
    }
  }
}
