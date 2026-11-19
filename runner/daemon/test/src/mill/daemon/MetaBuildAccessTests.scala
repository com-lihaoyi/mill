package mill.daemon

import mill.api.daemon.internal.LauncherLocking
import mill.internal.{LauncherLockRegistry, LauncherLockingImpl, LockUpgrade}
import utest.*

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

object MetaBuildAccessTests extends TestSuite {
  val tests = Tests {
    test("manyLaunchersEscalatingTogetherAllFinish") {
      val registry = LauncherLockRegistry()
      val sharedState = AtomicReference(RunnerSharedState.empty)
      val published = AtomicBoolean(false)
      val launcherCount = 15

      // Staggered starts make the probes overlap, so without queuing for Write
      // some launcher always holds the read lock and nobody ever publishes.
      val launchers = (1 to launcherCount).map { i =>
        val locking = LauncherLockingImpl(
          activeCommandMessage = s"launcher-$i",
          launcherPid = i.toLong,
          noBuildLock = false,
          noWaitForBuildLock = false,
          lockRegistry = registry,
          runId = s"run-$i"
        )
        val thread = Thread { () =>
          try {
            Thread.sleep(i * 20L)
            MetaBuildAccess(sharedState, locking)
              .withMetaBuild(depth = 1, LauncherLocking.WaitReporter.Noop) { (_, _) =>
                Thread.sleep(200)
                if (published.get()) LockUpgrade.Decision.Complete(())
                else LockUpgrade.Decision.Escalate
              } { _ =>
                published.set(true)
              }
          } finally locking.close()
        }
        thread.setDaemon(true)
        thread
      }

      launchers.foreach(_.start())
      val deadline = System.currentTimeMillis() + 30000
      launchers.foreach(t => t.join(math.max(1, deadline - System.currentTimeMillis())))

      assert(launchers.count(!_.isAlive) == launcherCount)
    }
  }
}
