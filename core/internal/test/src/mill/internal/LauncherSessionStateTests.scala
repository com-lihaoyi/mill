package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.api.daemon.internal.LauncherLocking.LockKind
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{CountDownLatch, TimeUnit}

object LauncherSessionStateTests extends TestSuite {
  val tests: Tests = Tests {
    test("artifact-locks-are-shared-per-path") {
      val state = new LauncherSessionState
      val lockA0 = state.artifactLockFor("/tmp/mill/out/mill-console-tail")
      val lockA1 = state.artifactLockFor("/tmp/mill/out/mill-console-tail")
      val lockB = state.artifactLockFor("/tmp/mill/out/mill-profile.json")

      assert(lockA0 eq lockA1)
      assert(!(lockA0 eq lockB))
    }

    test("meta-build-locks-are-independent-per-depth") {
      val state = new LauncherSessionState
      val blocker = new LauncherLockingImpl(
        activeCommandMessage = "depth-2-reader",
        launcherPid = 1001L,
        waitingErr = new PrintStream(new ByteArrayOutputStream()),
        noBuildLock = false,
        noWaitForBuildLock = false,
        launcherLocks = state,
        runId = "reader"
      )
      val contenderErrBytes = new ByteArrayOutputStream()
      val contender = new LauncherLockingImpl(
        activeCommandMessage = "depth-1-writer",
        launcherPid = 1002L,
        waitingErr = new PrintStream(contenderErrBytes),
        noBuildLock = false,
        noWaitForBuildLock = false,
        launcherLocks = state,
        runId = "writer"
      )

      val depth2Read = blocker.metaBuildLock(2, LockKind.Read)
      val acquired = new CountDownLatch(1)
      val depth1WriteRef = new AtomicReference[LauncherLocking.Lease]()
      val contenderThread = new Thread(() => {
        depth1WriteRef.set(contender.metaBuildLock(1, LockKind.Write))
        acquired.countDown()
      })
      contenderThread.start()

      assert(acquired.await(5, TimeUnit.SECONDS))
      assert(contenderErrBytes.size() == 0)

      depth1WriteRef.get().close()
      depth2Read.close()
      blocker.close()
      contender.close()
      contenderThread.join(3000)
    }
  }
}
