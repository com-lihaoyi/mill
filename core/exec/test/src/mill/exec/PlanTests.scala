package mill.exec

import mill.api.Task
import mill.api.Task.Simple
import mill.api.daemon.internal.LauncherLocking
import mill.api.TestGraphs
import utest.*

import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.Success

object PlanTests extends TestSuite {
  object transitiveLeaseChain extends mill.testkit.TestRootModule {
    def a = Task { 1 }
    def b = Task { a() + 1 }
    def c = Task { b() + 1 }
    lazy val millDiscover = mill.api.Discover[this.type]
  }

  private class TestLease extends LauncherLocking.Lease {
    val closed = AtomicBoolean(false)
    override def close(): Unit = closed.set(true)
  }

  private class TestLocking extends LauncherLocking {
    val versions = mutable.Map.empty[String, Long].withDefaultValue(0L)
    val contendedReads = mutable.Set.empty[String]

    private def key(path: Path) = path.toAbsolutePath.normalize().toString

    override def taskLock(
        path: Path,
        displayLabel: String,
        kind: LauncherLocking.LockKind,
        waitReporter: LauncherLocking.WaitReporter
    ): LauncherLocking.Lease = new TestLease

    override def taskVersion(path: Path): Long = versions(key(path))

    override def markTaskWritten(path: Path): Long = {
      val next = versions.valuesIterator.maxOption.getOrElse(0L) + 1L
      versions(key(path)) = next
      next
    }

    override def metaBuildLock(
        depth: Int,
        kind: LauncherLocking.LockKind,
        waitReporter: LauncherLocking.WaitReporter
    ): LauncherLocking.Lease = new TestLease

    override def tryMetaBuildWriteLock(
        depth: Int
    ): Either[LauncherLocking.Contention, LauncherLocking.Lease] = Right(new TestLease)

    override def awaitMetaBuildStateChange(depth: Int, timeoutMs: Long): Unit = ()

    override def tryTaskReadLock(
        path: Path,
        displayLabel: String
    ): Either[LauncherLocking.Contention, LauncherLocking.Lease] =
      if (contendedReads(key(path)))
        Left(LauncherLocking.Contention("blocked", displayLabel, Nil))
      else Right(new TestLease)

    override def tryTaskWriteLock(
        path: Path,
        displayLabel: String
    ): Either[LauncherLocking.Contention, LauncherLocking.Lease] = Right(new TestLease)

    override def awaitTaskStateChange(path: Path, displayLabel: String, timeoutMs: Long): Unit = ()

    override def exclusiveLock(
        kind: LauncherLocking.LockKind,
        waitReporter: LauncherLocking.WaitReporter
    ): LauncherLocking.Lease = new TestLease

    override def withReleasedExclusive[T](
        waitReporter: LauncherLocking.WaitReporter
    )(body: => T): T = body

    override def close(): Unit = ()
  }

  def checkTopological(tasks: Seq[Task[?]]) = {
    val seen = mutable.Set.empty[Task[?]]
    for (t <- tasks.reverseIterator) {
      seen.add(t)
      for (upstream <- t.inputs) {
        assert(!seen(upstream))
      }
    }
  }

  val tests = Tests {

    import TestGraphs.*

    test("topoSortedTransitiveTasks") {
      def check(tasks: Seq[Task[?]], expected: Seq[Task[?]]) = {
        val result = PlanImpl.topoSorted(PlanImpl.transitiveTasks(tasks)).values
        checkTopological(result)
        assert(result == expected)
      }

      test("singleton") - check(
        tasks = Seq(singleton.single),
        expected = Seq(singleton.single)
      )
      test("backtickIdentifiers") - check(
        tasks = Seq(bactickIdentifiers.`a-down-task`),
        expected = Seq(bactickIdentifiers.`up-task`, bactickIdentifiers.`a-down-task`)
      )
      test("pair") - check(
        tasks = Seq(pair.down),
        expected = Seq(pair.up, pair.down)
      )
      test("anonTriple") - check(
        tasks = Seq(anonTriple.down),
        expected = Seq(anonTriple.up, anonTriple.down.inputs(0), anonTriple.down)
      )
      test("diamond") - check(
        tasks = Seq(diamond.down),
        expected = Seq(diamond.up, diamond.left, diamond.right, diamond.down)
      )
      test("anonDiamond") - check(
        tasks = Seq(diamond.down),
        expected = Seq(
          diamond.up,
          diamond.down.inputs(0),
          diamond.down.inputs(1),
          diamond.down
        )
      )
    }
    test("groupAroundNamedTasks") {
      def check[T, R <: Simple[Int]](base: T)(
          task: T => R,
          important0: Seq[T => Simple[?]],
          expected: Seq[(R, Int)]
      ) = {

        val topoSorted = PlanImpl.topoSorted(PlanImpl.transitiveTasks(Seq(task(base))))

        val important = important0.map(_(base))
        val grouped = PlanImpl.groupAroundImportantTasks(topoSorted) {
          case t: Task.Computed[_] if important.contains(t) => t: Simple[?]
        }
        val flattened = Seq.from(grouped.values().flatten)

        checkTopological(flattened)
        for ((terminal, expectedSize) <- expected) {
          val grouping = grouped.lookupKey(terminal)
          assert(
            grouping.size == expectedSize,
            grouping.flatMap(_.asSimple: Option[Simple[?]]).filter(important.contains) == Seq(
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
        _.`a-down-task`,
        Seq(_.`up-task`, _.`a-down-task`),
        Seq(
          bactickIdentifiers.`up-task` -> 1,
          bactickIdentifiers.`a-down-task` -> 1
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

        val topoSorted = PlanImpl.topoSorted(
          PlanImpl.transitiveTasks(Seq.from(goals))
        )
        val grouped = PlanImpl.groupAroundImportantTasks(topoSorted) {
          case t: Task.Named[Any] => t
          case t if goals.contains(t) => t
        }
        grouped.keyCount
      }

      test("separateGroups") {
        import separateGroups.*
        val groupCount = countGroups(right, left)
        assert(groupCount == 2)
      }

      test("triangleTask") {
        // Make sure the following graph ends up as a single group, since although
        // `right` depends on `left`, both of them depend on the un-cached `task`
        // which would force them both to re-compute every time `task` changes
        import triangleTask.*
        val groupCount = countGroups(right, left)
        assert(groupCount == 2)
      }

      test("multiTerminalGroup") {
        // Make sure the following graph ends up as two groups
        import multiTerminalGroup.*
        val groupCount = countGroups(right, left)
        assert(groupCount == 2)
      }

      test("multiTerminalBoundary") {
        // Make sure the following graph ends up as three groups: one for
        // each cached task, and one for the downstream task we are running
        import multiTerminalBoundary.*
        val groupCount = countGroups(task2)
        assert(groupCount == 3)
      }
    }
    test("leaseTrackerRetainsUntilTransitiveDownstreamsComplete") {
      import transitiveLeaseChain.*

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(
          a -> Nil,
          b -> Seq(a),
          c -> Seq(b)
        )
      )

      val aLease = new TestLease
      val bLease = new TestLease
      val cLease = new TestLease

      tracker.retain(a, Paths.get("/tmp/mill-lock-test/a"), "a", aLease, 0L)
      tracker.retain(b, Paths.get("/tmp/mill-lock-test/b"), "b", bLease, 0L)
      tracker.retain(c, Paths.get("/tmp/mill-lock-test/c"), "c", cLease, 0L)

      tracker.onCompleted(a)
      assert(!aLease.closed.get())
      assert(!bLease.closed.get())
      assert(!cLease.closed.get())

      tracker.onCompleted(b)
      assert(!aLease.closed.get())
      assert(!bLease.closed.get())
      assert(!cLease.closed.get())

      tracker.onCompleted(c)
      assert(aLease.closed.get())
      assert(bLease.closed.get())
      assert(cLease.closed.get())
    }

    test("onCompletedFiresEvenWhenUpstreamFutureFails") {
      // Mirrors the wire-up in `evaluateTerminals`: each task's future is
      // chained via `Future.sequence(deps).map { body }.andThen { onCompleted }`.
      // When `b`'s body throws, `c`'s `Future.sequence([b])` fails and the
      // .map body never runs — but the `andThen` cleanup must still fire
      // for both `b` and `c` so `a`'s retained Read lease is released
      // before drain. This is the orphan-lease bug the andThen fixes.
      import transitiveLeaseChain.*
      import scala.concurrent.ExecutionContext.Implicits.global

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(a -> Nil, b -> Seq(a), c -> Seq(b))
      )
      val aLease = new TestLease
      tracker.retain(a, Paths.get("/tmp/mill-lock-test/a"), "a", aLease, 0L)

      val aFuture: Future[Option[Unit]] =
        Future.successful(Some(())).andThen { case _ => tracker.onCompleted(a) }

      val bFuture: Future[Option[Unit]] =
        aFuture.map(_ => throw RuntimeException("boom"))
          .andThen { case _ => tracker.onCompleted(b) }

      val cFuture: Future[Option[Unit]] =
        Future.sequence(Seq(bFuture)).map(_ => Some(()))
          .andThen { case _ => tracker.onCompleted(c) }

      val settled = Future.sequence(
        Seq(aFuture, bFuture, cFuture).map(_.transform(r => Success(r)))
      )
      Await.result(settled, 5.seconds)

      // Both b's and c's onCompleted must have fired, recursively draining
      // b's subtree and then releasing a's retained Read lease.
      assert(aLease.closed.get())
    }

    test("leaseTrackerDropsInactiveHigherLocksAndRetriesOnVersionChange") {
      import transitiveLeaseChain.*

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(a -> Nil, b -> Seq(a), c -> Seq(b))
      )
      val locking = new TestLocking
      val aPath = Paths.get("/tmp/mill-lock-test/a")
      val bPath = Paths.get("/tmp/mill-lock-test/b")
      val aLease = new TestLease
      val bLease = new TestLease

      tracker.retain(a, aPath, "a", aLease, 0L)
      tracker.retain(b, bPath, "b", bLease, 0L)
      tracker.releaseHigherThan(aPath.toAbsolutePath.normalize().toString)

      assert(!aLease.closed.get())
      assert(bLease.closed.get())

      locking.markTaskWritten(bPath)
      val retry =
        try {
          tracker.reacquireDropped(locking, LauncherLocking.WaitReporter.Noop)
          throw new java.lang.AssertionError("expected retry")
        } catch {
          case e: Execution.RetryDueToDroppedTaskLock => e
        }
      assert(retry.label == "b")
      tracker.drain()
    }

    test("leaseTrackerNonBlockingReacquireRetriesOnContention") {
      import transitiveLeaseChain.*

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(a -> Nil, b -> Seq(a), c -> Seq(b))
      )
      val locking = new TestLocking
      val aPath = Paths.get("/tmp/mill-lock-test/a")
      val bPath = Paths.get("/tmp/mill-lock-test/b")
      val bLease = new TestLease

      tracker.retain(b, bPath, "b", bLease, 0L)
      tracker.releaseHigherThan(aPath.toAbsolutePath.normalize().toString)

      locking.contendedReads.add(bPath.toAbsolutePath.normalize().toString)
      val retry =
        try {
          tracker.reacquireDropped(locking, LauncherLocking.WaitReporter.Noop, block = false)
          throw new java.lang.AssertionError("expected retry")
        } catch {
          case e: Execution.RetryDueToDroppedTaskLock => e
        }

      assert(retry.label == "b")
      tracker.drain()
    }

    // `hasDropped` gates the per-task read-lock fast path: it must read false
    // whenever no read is dropped, so the common no-op build skips the O(n)
    // `reacquireDropped` scan (and the `executionContext.blocking` pool churn)
    // entirely. `reacquireDropped`/`hasDropped` are backed by the
    // `droppedStates` work-list, so this also pins that membership in step with
    // the `dropped` flag across multi-drop, reacquire, redundant reacquire, and
    // drain.
    test("leaseTrackerHasDroppedTracksDropReacquireAndDrain") {
      import transitiveLeaseChain.*

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(a -> Nil, b -> Seq(a), c -> Seq(b))
      )
      val locking = new TestLocking
      val aPath = Paths.get("/tmp/mill-lock-test/a")
      val bPath = Paths.get("/tmp/mill-lock-test/b")
      val cPath = Paths.get("/tmp/mill-lock-test/c")
      val aKey = aPath.toAbsolutePath.normalize().toString

      tracker.retain(a, aPath, "a", new TestLease, 0L)
      tracker.retain(b, bPath, "b", new TestLease, 0L)
      tracker.retain(c, cPath, "c", new TestLease, 0L)
      assert(!tracker.hasDropped)

      // Drop everything sorting after `a` (both `b` and `c`): the work-list now
      // holds two entries that `reacquireDropped` must iterate, not the whole
      // graph.
      tracker.releaseHigherThan(aKey)
      assert(tracker.hasDropped)

      // Clean reacquire (no version change, no contention) clears every drop.
      tracker.reacquireDropped(locking, LauncherLocking.WaitReporter.Noop)
      assert(!tracker.hasDropped)

      // With nothing dropped, a redundant reacquire is a no-op fast return.
      tracker.reacquireDropped(locking, LauncherLocking.WaitReporter.Noop)
      assert(!tracker.hasDropped)

      // Draining a still-dropped retained must also remove its `droppedStates`
      // entry, otherwise `hasDropped` would stay stuck true and disable the
      // fast path.
      tracker.releaseHigherThan(aKey)
      assert(tracker.hasDropped)
      tracker.drain()
      assert(!tracker.hasDropped)
    }

    test("leaseTrackerDoesNotDropActivelyConsumedHigherLocks") {
      import transitiveLeaseChain.*

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(a -> Nil, b -> Seq(a), c -> Seq(b))
      )
      val locking = new TestLocking
      val aPath = Paths.get("/tmp/mill-lock-test/a")
      val bPath = Paths.get("/tmp/mill-lock-test/b")
      val bLease = new TestLease

      tracker.retain(b, bPath, "b", bLease, 0L)
      tracker.withActiveConsumers(
        c,
        () => tracker.reacquireDropped(locking, LauncherLocking.WaitReporter.Noop, block = true)
      ) {
        tracker.releaseHigherThan(aPath.toAbsolutePath.normalize().toString)
        assert(!bLease.closed.get())
      }
      tracker.releaseHigherThan(aPath.toAbsolutePath.normalize().toString)
      assert(bLease.closed.get())
      tracker.drain()
    }

    // A read dropped before its downstream becomes an active consumer must be
    // reacquired by `withActiveConsumers` before the body runs, not left
    // released. Here the dropped upstream `b` was rewritten by a peer while
    // dropped, so the reacquire must detect the version change and retry rather
    // than let the consumer body read a stale/overwritten output.
    test("withActiveConsumersReacquiresAndValidatesReadsDroppedBeforeActivation") {
      import transitiveLeaseChain.*

      val tracker = new Execution.LeaseTracker(
        Array(a, b, c),
        Map(a -> Nil, b -> Seq(a), c -> Seq(b))
      )
      val locking = new TestLocking
      val aPath = Paths.get("/tmp/mill-lock-test/a")
      val bPath = Paths.get("/tmp/mill-lock-test/b")
      val bLease = new TestLease

      tracker.retain(b, bPath, "b", bLease, 0L)
      // Sibling future drops `b` (higher key than `a`) while no consumer is active.
      tracker.releaseHigherThan(aPath.toAbsolutePath.normalize().toString)
      assert(bLease.closed.get())

      // Peer rewrites `b` while it is dropped.
      locking.markTaskWritten(bPath)

      var bodyRan = false
      val retry =
        try {
          tracker.withActiveConsumers(
            c,
            () => tracker.reacquireDropped(locking, LauncherLocking.WaitReporter.Noop, block = true)
          ) {
            bodyRan = true
          }
          throw new java.lang.AssertionError("expected retry")
        } catch {
          case e: Execution.RetryDueToDroppedTaskLock => e
        }

      // The consumer body must not have run with `b` unprotected/stale.
      assert(!bodyRan)
      assert(retry.label == "b")
      // The active-consumer count must be released even though reacquire threw.
      tracker.drain()
    }

  }
}
