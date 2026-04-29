package mill.exec

import mill.api.Task
import mill.api.Task.Simple
import mill.api.daemon.internal.LauncherLocking
import mill.api.TestGraphs
import mill.internal.{LauncherLockRegistry, LauncherLockingImpl, LockUpgrade}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

object PlanTests extends TestSuite {
  object transitiveLeaseChain extends mill.testkit.TestRootModule {
    def a = Task { 1 }
    def b = Task { a() + 1 }
    def c = Task { b() + 1 }
    lazy val millDiscover = mill.api.Discover[this.type]
  }

  object lockPhaseOrdering extends mill.testkit.TestRootModule {
    def up = Task { 1 }
    def extra = Task { up() + 1 }
    def down = Task { up() + 1 }
    def side = Task { 2 }
    lazy val millDiscover = mill.api.Discover[this.type]
  }

  private class TestLease extends LauncherLocking.Lease {
    val closed = new AtomicBoolean(false)
    override def close(): Unit = closed.set(true)
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

      tracker.retain(a, aLease)
      tracker.retain(b, bLease)
      tracker.retain(c, cLease)

      tracker.onCompleted(b)
      assert(!aLease.closed.get())
      assert(!bLease.closed.get())
      assert(!cLease.closed.get())

      tracker.onCompleted(c)
      assert(aLease.closed.get())
      assert(bLease.closed.get())
      assert(cLease.closed.get())
    }

    test("leaseTrackerOrdersLockPhaseByHeightThenLockKey") {
      import lockPhaseOrdering.*

      val upTask = up
      val downTask = down
      val sideTask = side
      // down depends on up so down has height 1, up and side have height 0.
      // Within height 0, up and side tiebreak by lock key. Keys chosen so that
      // side's key sorts before up's, giving order: side, up, down.
      val keys = Map[Task[?], String](
        downTask -> "9-down",
        sideTask -> "1-side",
        upTask -> "5-up"
      )
      val tracker = new Execution.LeaseTracker(
        Array(upTask, downTask, sideTask),
        Map(
          upTask -> Nil,
          downTask -> Seq(upTask),
          sideTask -> Nil
        ),
        labelled => keys(labelled)
      )
      val batch = Set[Task[?]](upTask, downTask, sideTask)

      val prereqs = tracker.taskLockPhasePrerequisites(batch)

      assert(tracker.taskLockPhaseOrder(batch) == Seq(sideTask, upTask, downTask))
      assert(!prereqs.contains(sideTask))
      assert(prereqs.contains(upTask))
      assert(prereqs.contains(downTask))
      assert(!prereqs(upTask).isCompleted)
      assert(!prereqs(downTask).isCompleted)

      tracker.onTaskLockPhaseComplete(sideTask)
      assert(prereqs(upTask).isCompleted)
      assert(!prereqs(downTask).isCompleted)

      tracker.onTaskLockPhaseComplete(upTask)
      assert(prereqs(downTask).isCompleted)
    }

    test("leaseTrackerFiltersLockPhaseOrderingToCurrentBatch") {
      import lockPhaseOrdering.*

      val upTask = up
      val downTask = down
      val sideTask = side
      val keys = Map[Task[?], String](
        downTask -> "9-down",
        sideTask -> "1-side",
        upTask -> "5-up"
      )
      val tracker = new Execution.LeaseTracker(
        Array(upTask, downTask, sideTask),
        Map(
          upTask -> Nil,
          downTask -> Seq(upTask),
          sideTask -> Nil
        ),
        labelled => keys(labelled)
      )

      val batchWithoutSide = Set[Task[?]](upTask, downTask)
      val prereqs = tracker.taskLockPhasePrerequisites(batchWithoutSide)

      assert(tracker.taskLockPhaseOrder(batchWithoutSide) == Seq(upTask, downTask))
      assert(!prereqs.contains(upTask))
      assert(prereqs.contains(downTask))
    }

    test("leaseTrackerCompletionReleasesLockPhaseOrdering") {
      import lockPhaseOrdering.*

      val upTask = up
      val sideTask = side
      val keys = Map[Task[?], String](
        sideTask -> "1-side",
        upTask -> "5-up"
      )
      val tracker = new Execution.LeaseTracker(
        Array(upTask, sideTask),
        Map(
          upTask -> Nil,
          sideTask -> Nil
        ),
        labelled => keys(labelled)
      )
      val batch = Set[Task[?]](upTask, sideTask)
      val upPrereq = tracker.taskLockPhasePrerequisites(batch)(upTask)

      assert(!upPrereq.isCompleted)
      tracker.onCompleted(sideTask)
      assert(upPrereq.isCompleted)
    }

    test("leaseTrackerLockPhaseOrderAgreesAcrossLaunchersOnSharedTasks") {
      // Two launchers planning different goal subsets that share two named
      // tasks. The intrinsic namedUpstreamHeight depends only on each task's
      // own input chain, so the two launchers must agree on the lock-phase
      // order of the shared pair regardless of which other tasks are in their
      // respective plans. Under the old plan-relative depth this could differ.
      import lockPhaseOrdering.*

      val upTask = up
      val extraTask = extra
      val downTask = down
      val keys = Map[Task[?], String](
        downTask -> "1-down",
        extraTask -> "2-extra",
        upTask -> "3-up"
      )
      val largerPlan = new Execution.LeaseTracker(
        Array(upTask, extraTask, downTask),
        Map(
          upTask -> Nil,
          extraTask -> Seq(upTask),
          downTask -> Seq(upTask)
        ),
        labelled => keys(labelled)
      )
      val smallerPlan = new Execution.LeaseTracker(
        Array(upTask, downTask),
        Map(
          upTask -> Nil,
          downTask -> Seq(upTask)
        ),
        labelled => keys(labelled)
      )
      val shared = Set[Task[?]](upTask, downTask)

      // up has height 0, down has height 1 in BOTH plans (computed from
      // task definitions, not plan grouping).
      assert(largerPlan.taskLockPhaseOrder(shared) == Seq(upTask, downTask))
      assert(smallerPlan.taskLockPhaseOrder(shared) == Seq(upTask, downTask))
    }

    test("leaseTrackerLockPhaseOrderIgnoresAnonymousIntermediaryGrouping") {
      // multiTerminalGroup has an anonymous Task.Anon shared by named `left`
      // and `right`. Under old plan-relative depth, whichever named task got
      // grouped with `task` (a function of topo order, which depends on
      // goals) had different depth than the other. Under namedUpstreamHeight,
      // both left and right have height 0 because their first named ancestor
      // walk through the anonymous intermediary terminates with no named
      // tasks. Two launchers with different goal sets must produce the same
      // order on the shared pair.
      import TestGraphs.multiTerminalGroup.*

      val keys = Map[Task[?], String](
        left -> "1-left",
        right -> "2-right"
      )
      val planA = new Execution.LeaseTracker(
        Array[Task[?]](left, right),
        Map[Task[?], Seq[Task[?]]](left -> Nil, right -> Nil),
        labelled => keys(labelled)
      )
      val planB = new Execution.LeaseTracker(
        Array[Task[?]](right, left),
        Map[Task[?], Seq[Task[?]]](right -> Nil, left -> Nil),
        labelled => keys(labelled)
      )
      val shared = Set[Task[?]](left, right)

      assert(planA.taskLockPhaseOrder(shared) == Seq(left, right))
      assert(planB.taskLockPhaseOrder(shared) == Seq(left, right))
    }

    test("leaseTrackerLockPhaseGatePreventsOppositeOrderDeadlockWithRealLocks") {
      import lockPhaseOrdering.*

      val alphaTask = up
      val betaTask = side
      val aGoal = down
      val bGoal = extra
      val workspace = os.temp.dir(prefix = "mill-lock-phase-test")
      val alphaPath = workspace / "alpha.dest"
      val betaPath = workspace / "beta.dest"
      val registry = new LauncherLockRegistry
      val executor = Executors.newFixedThreadPool(8)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(executor)

      // Records each launcher's lock-acquisition order so we can assert the
      // gate actually serialized them in the canonical order, not just that
      // the run didn't deadlock.
      val acquisitionLog = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()

      def locking(command: String, pid: Long) = new LauncherLockingImpl(
        activeCommandMessage = command,
        launcherPid = pid,
        waitingErr = new PrintStream(new ByteArrayOutputStream()),
        noBuildLock = false,
        noWaitForBuildLock = false,
        lockRegistry = registry,
        runId = command
      )

      val lockingA = locking("launcher-a", 101)
      val lockingB = locking("launcher-b", 102)

      def lockKey(task: Task.Named[?]): String =
        if (task == alphaTask) alphaPath.toNIO.toAbsolutePath.normalize().toString
        else if (task == betaTask) betaPath.toNIO.toAbsolutePath.normalize().toString
        else (workspace / s"${task.ctx.segments.render}.dest").toNIO.toAbsolutePath
          .normalize()
          .toString

      val trackerA = new Execution.LeaseTracker(
        Array(alphaTask, betaTask, aGoal),
        Map(
          alphaTask -> Nil,
          betaTask -> Nil,
          aGoal -> Seq(alphaTask, betaTask)
        ),
        lockKey
      )
      val trackerB = new Execution.LeaseTracker(
        Array(alphaTask, betaTask, bGoal),
        Map(
          alphaTask -> Nil,
          betaTask -> Nil,
          bGoal -> Seq(alphaTask, betaTask)
        ),
        lockKey
      )
      val batch = Set[Task[?]](alphaTask, betaTask)
      val prereqsA = trackerA.taskLockPhasePrerequisites(batch)
      val prereqsB = trackerB.taskLockPhasePrerequisites(batch)

      def runCacheMiss(
          launcher: String,
          taskLabel: String,
          locking: LauncherLockingImpl,
          tracker: Execution.LeaseTracker,
          task: Task.Named[?],
          path: os.Path,
          prereqs: Map[Task[?], Future[Unit]]
      ): Future[Unit] = Future {
        Await.result(prereqs.getOrElse(task, Future.successful(())), 5.seconds)
        try {
          LockUpgrade.readThenWrite(
            acquireRead =
              locking.taskLock(path.toNIO, task.toString, LauncherLocking.LockKind.Read),
            acquireWrite = {
              val lease =
                locking.taskLock(path.toNIO, task.toString, LauncherLocking.LockKind.Write)
              acquisitionLog.add((launcher, taskLabel))
              tracker.onTaskLockPhaseComplete(task)
              lease
            }
          )(_ => LockUpgrade.Decision.Escalate) { scope =>
            tracker.retain(task, scope.downgradeAndRetain())
          }
        } finally tracker.onCompleted(task)
      }

      try {
        // Schedule each launcher's two cache-miss escalations in opposite
        // order: A starts on alpha, B starts on beta. Without the gate this is
        // the classic AB/BA cycle (A holds Read alpha + waits Write beta, B
        // holds Read beta + waits Write alpha).
        val aAlpha = runCacheMiss("A", "alpha", lockingA, trackerA, alphaTask, alphaPath, prereqsA)
        val bBeta = runCacheMiss("B", "beta", lockingB, trackerB, betaTask, betaPath, prereqsB)
        val aBeta = runCacheMiss("A", "beta", lockingA, trackerA, betaTask, betaPath, prereqsA)
        val bAlpha = runCacheMiss("B", "alpha", lockingB, trackerB, alphaTask, alphaPath, prereqsB)

        val aDone = Future.sequence(Seq(aAlpha, aBeta)).map(_ => trackerA.onCompleted(aGoal))
        val bDone = Future.sequence(Seq(bBeta, bAlpha)).map(_ => trackerB.onCompleted(bGoal))

        val _ = Await.result(Future.sequence(Seq(aDone, bDone)), 10.seconds)

        // Gate must serialize each launcher's acquisitions in canonical order
        // (alpha < beta by lock path), regardless of which Future was scheduled
        // first.
        import scala.jdk.CollectionConverters.*
        val perLauncher = acquisitionLog.asScala.toSeq.groupMap(_._1)(_._2)
        assert(perLauncher("A") == Seq("alpha", "beta"))
        assert(perLauncher("B") == Seq("alpha", "beta"))
      } finally {
        lockingA.close()
        lockingB.close()
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        os.remove.all(workspace)
      }
    }

  }
}
