package mill.api.daemon

import mill.constants.{DaemonFiles, OutFiles}
import utest.*

import java.io.PrintStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WorkspaceLockingTests extends TestSuite {
  val tests: Tests = Tests {
    test("finished-run-keeps-latest-profile-links") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        val manager = new WorkspaceLocking.InProcessManager(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = "test-command",
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )
        val launcherRunFile =
          out / OutFiles.millDaemon / os.RelPath(DaemonFiles.launcherRun(manager.runId))

        val profilePath = os.Path(manager.runFileJava((out / OutFiles.millProfile).toNIO))
        val chromePath =
          os.Path(manager.runFileJava((out / OutFiles.millChromeProfile).toNIO))
        val dependencyTreePath =
          os.Path(manager.runFileJava((out / OutFiles.millDependencyTree).toNIO))
        val invalidationTreePath =
          os.Path(manager.runFileJava((out / OutFiles.millInvalidationTree).toNIO))
        val consoleTailPath = os.Path(manager.consoleTailJava)

        os.write.over(consoleTailPath, "tail")
        os.write.over(profilePath, "profile")
        os.write.over(chromePath, "chrome")
        os.write.over(dependencyTreePath, """{"kind":"dependency"}""")
        os.write.over(invalidationTreePath, """{"kind":"invalidation"}""")

        manager.acquireLocks(Seq.empty).close()

        assert(os.exists(launcherRunFile))
        assert(os.read(launcherRunFile).contains(""""pid":12345"""))
        assert(os.read(launcherRunFile).contains("test-command"))

        assert(os.exists(out / DaemonFiles.millConsoleTail))
        assert(os.exists(out / OutFiles.millProfile))
        assert(os.exists(out / OutFiles.millChromeProfile))
        assert(os.exists(out / OutFiles.millDependencyTree))
        assert(os.exists(out / OutFiles.millInvalidationTree))

        assert(Files.isSymbolicLink((out / OutFiles.millProfile).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millChromeProfile).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millDependencyTree).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millInvalidationTree).toNIO))
        assert(Files.readSymbolicLink(
          (out / OutFiles.millProfile).toNIO
        ).toString.startsWith("mill-run/"))
        assert(Files.readSymbolicLink(
          (out / OutFiles.millChromeProfile).toNIO
        ).toString.startsWith("mill-run/"))

        manager.close()

        assert(!os.exists(launcherRunFile))

        assert(os.exists(out / DaemonFiles.millConsoleTail))
        assert(os.exists(out / OutFiles.millProfile))
        assert(os.exists(out / OutFiles.millChromeProfile))
        assert(os.exists(out / OutFiles.millDependencyTree))
        assert(os.exists(out / OutFiles.millInvalidationTree))

        assert(os.read(out / OutFiles.millProfile) == "profile")
        assert(os.read(out / OutFiles.millChromeProfile) == "chrome")
        assert(os.read(out / OutFiles.millDependencyTree) == """{"kind":"dependency"}""")
        assert(os.read(out / OutFiles.millInvalidationTree) == """{"kind":"invalidation"}""")
        assert(Files.isSymbolicLink((out / OutFiles.millProfile).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millChromeProfile).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millDependencyTree).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millInvalidationTree).toNIO))
        assert(Files.readSymbolicLink(
          (out / OutFiles.millProfile).toNIO
        ).toString.startsWith("mill-run/"))
        assert(Files.readSymbolicLink(
          (out / OutFiles.millChromeProfile).toNIO
        ).toString.startsWith("mill-run/"))
      }
    }

    test("closing-lease-after-manager-close-does-not-resurrect-launcher-run-file") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        val manager = new WorkspaceLocking.InProcessManager(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = "test-command",
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )
        val launcherRunFile =
          out / OutFiles.millDaemon / os.RelPath(DaemonFiles.launcherRun(manager.runId))
        val lease = manager.acquireLock(
          WorkspaceLocking.metaBuildResource(0, WorkspaceLocking.LockKind.Write)
        )
        lease.downgradeToRead()

        manager.close()
        assert(!os.exists(launcherRunFile))

        lease.close()
        assert(!os.exists(launcherRunFile))
      }
    }

    test("latest-links-fall-back-to-most-recent-run-that-published-each-file") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        def manager(command: String) = new WorkspaceLocking.InProcessManager(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = command,
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )

        val first = manager("first")
        val firstProfilePath = os.Path(first.runFileJava((out / OutFiles.millProfile).toNIO))
        os.write.over(firstProfilePath, "first-profile")
        first.acquireLocks(Seq.empty).close()

        val second = manager("second")
        val secondChromePath =
          os.Path(second.runFileJava((out / OutFiles.millChromeProfile).toNIO))
        os.write.over(secondChromePath, "second-chrome")
        second.acquireLocks(Seq.empty).close()

        assert(os.read(out / OutFiles.millProfile) == "first-profile")
        assert(os.read(out / OutFiles.millChromeProfile) == "second-chrome")

        first.close()
        second.close()
      }
    }

    test("concurrent-managers-respect-fair-lock-order") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)
        val waitingBytes = new ByteArrayOutputStream()
        val waitingErr = new PrintStream(waitingBytes)

        def manager(command: String) = new WorkspaceLocking.InProcessManager(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = command,
          launcherPid = 12345L,
          waitingErr = waitingErr,
          noBuildLock = false,
          noWaitForBuildLock = false
        )

        val firstReaderManager = manager("first-reader")
        val writerManager = manager("writer")
        val secondReaderManager = manager("second-reader")
        val resource = WorkspaceLocking.Resource("fair-resource", WorkspaceLocking.LockKind.Read)
        val firstReadLease = firstReaderManager.acquireLock(resource)
        val writerAcquired = new CountDownLatch(1)
        val releaseWriter = new CountDownLatch(1)
        val secondReaderAcquired = new CountDownLatch(1)
        @volatile var writerLease: WorkspaceLocking.ResourceLease = null
        @volatile var secondReadLease: WorkspaceLocking.ResourceLease = null

        val writerThread = new Thread(() => {
          writerLease = writerManager.acquireLock(resource.copy(kind = WorkspaceLocking.LockKind.Write))
          writerAcquired.countDown()
          releaseWriter.await(5, TimeUnit.SECONDS)
          writerLease.close()
        })
        writerThread.start()

        assertEventually(waitingBytes.size() > 0)

        val secondReaderThread = new Thread(() => {
          secondReadLease = secondReaderManager.acquireLock(resource)
          secondReaderAcquired.countDown()
        })
        secondReaderThread.start()

        firstReadLease.close()

        assert(writerAcquired.await(5, TimeUnit.SECONDS))
        assert(!secondReaderAcquired.await(100, TimeUnit.MILLISECONDS))

        releaseWriter.countDown()
        assert(secondReaderAcquired.await(5, TimeUnit.SECONDS))

        secondReadLease.close()
        writerThread.join(5000)
        secondReaderThread.join(5000)
        firstReaderManager.close()
        writerManager.close()
        secondReaderManager.close()
      }
    }

    test("no-wait-reader-does-not-barge-ahead-of-queued-writer") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)
        val waitingBytes = new ByteArrayOutputStream()
        val waitingErr = new PrintStream(waitingBytes)

        def manager(command: String, noWait: Boolean = false) =
          new WorkspaceLocking.InProcessManager(
            out = out,
            daemonDir = out / OutFiles.millDaemon,
            activeCommandMessage = command,
            launcherPid = 12345L,
            waitingErr = waitingErr,
            noBuildLock = false,
            noWaitForBuildLock = noWait
          )

        val firstReaderManager = manager("first-reader")
        val writerManager = manager("writer")
        val noWaitReaderManager = manager("no-wait-reader", noWait = true)
        val resource = WorkspaceLocking.Resource("no-wait-fair-resource", WorkspaceLocking.LockKind.Read)
        val firstReadLease = firstReaderManager.acquireLock(resource)
        val writerAcquired = new CountDownLatch(1)
        val releaseWriter = new CountDownLatch(1)
        @volatile var writerLease: WorkspaceLocking.ResourceLease = null

        val writerThread = new Thread(() => {
          writerLease = writerManager.acquireLock(resource.copy(kind = WorkspaceLocking.LockKind.Write))
          writerAcquired.countDown()
          releaseWriter.await(5, TimeUnit.SECONDS)
          writerLease.close()
        })
        writerThread.start()
        assertEventually(waitingBytes.size() > 0)

        val ex =
          try {
            noWaitReaderManager.acquireLock(resource)
            throw new java.lang.AssertionError("expected no-wait acquisition to fail")
          } catch {
            case e: Exception => e
          }
        assert(ex.getMessage.contains("no-wait-fair-resource"))

        firstReadLease.close()
        assert(writerAcquired.await(5, TimeUnit.SECONDS))
        releaseWriter.countDown()
        writerThread.join(5000)
        firstReaderManager.close()
        writerManager.close()
        noWaitReaderManager.close()
      }
    }
  }

  private def withTmpDir[T](body: os.Path => T): T = {
    val tmpDir = os.Path(Files.createTempDirectory("workspace-locking-tests"))
    try body(tmpDir)
    finally os.remove.all(tmpDir)
  }

  private def assertEventually(predicate: => Boolean): Unit = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!predicate && System.nanoTime() < deadline) Thread.sleep(10)
    if (!predicate) throw new java.lang.AssertionError("predicate did not become true")
  }
}
