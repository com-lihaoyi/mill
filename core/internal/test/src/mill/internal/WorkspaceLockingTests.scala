package mill.internal

import mill.api.daemon.internal.LauncherLocking
import mill.constants.{DaemonFiles, OutFiles}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}

object WorkspaceLockingTests extends TestSuite {
  val tests: Tests = Tests {
    test("finished-run-keeps-latest-profile-links") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        val manager = new LauncherLockingImpl(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = "test-command",
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )
        val launcherRunFile =
          out / OutFiles.millDaemon / os.RelPath(DaemonFiles.perLauncherFilePath(manager.runId))

        val profilePath = os.Path(manager.artifactPath((out / OutFiles.millProfile).toNIO))
        val chromePath = os.Path(manager.artifactPath((out / OutFiles.millChromeProfile).toNIO))
        val dependencyTreePath = os.Path(manager.artifactPath((out / OutFiles.millDependencyTree).toNIO))
        val invalidationTreePath = os.Path(manager.artifactPath((out / OutFiles.millInvalidationTree).toNIO))
        val consoleTailPath = os.Path(manager.consoleTail)

        os.write.over(consoleTailPath, "tail")
        os.write.over(profilePath, "profile")
        os.write.over(chromePath, "chrome")
        os.write.over(dependencyTreePath, """{"kind":"dependency"}""")
        os.write.over(invalidationTreePath, """{"kind":"invalidation"}""")

        manager.publishArtifacts()

        assert(os.exists(launcherRunFile))
        assert(os.read(launcherRunFile).contains(""""pid":12345"""))
        assert(os.read(launcherRunFile).contains("test-command"))

        assert(os.exists(out / DaemonFiles.millConsoleTail))
        assert(os.exists(out / OutFiles.millProfile))
        assert(os.exists(out / OutFiles.millChromeProfile))
        assert(os.exists(out / OutFiles.millDependencyTree))
        assert(os.exists(out / OutFiles.millInvalidationTree))

        assert(os.isLink(out / OutFiles.millProfile))
        assert(os.isLink(out / OutFiles.millChromeProfile))
        assert(os.isLink(out / OutFiles.millDependencyTree))
        assert(os.isLink(out / OutFiles.millInvalidationTree))
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
        assert(os.isLink(out / OutFiles.millProfile))
        assert(os.isLink(out / OutFiles.millChromeProfile))
        assert(os.isLink(out / OutFiles.millDependencyTree))
        assert(os.isLink(out / OutFiles.millInvalidationTree))
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

        val manager = new LauncherLockingImpl(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = "test-command",
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )
        val launcherRunFile =
          out / OutFiles.millDaemon / os.RelPath(DaemonFiles.perLauncherFilePath(manager.runId))
        val lease = manager.metaBuildLock(LauncherLocking.LockKind.Write)
        lease.downgradeToRead()

        manager.close()
        assert(!os.exists(launcherRunFile))

        lease.close()
        assert(!os.exists(launcherRunFile))
      }
    }

    test("manager-close-releases-outstanding-locks") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        def manager(command: String, noWait: Boolean = false) =
          new LauncherLockingImpl(
            out = out,
            daemonDir = out / OutFiles.millDaemon,
            activeCommandMessage = command,
            launcherPid = 12345L,
            waitingErr = new PrintStream(System.err),
            noBuildLock = false,
            noWaitForBuildLock = noWait
          )

        val resource = out / "close-release"
        val first = manager("first")
        first.taskLock(resource.toNIO, LauncherLocking.LockKind.Write)
        first.close()

        val second = manager("second", noWait = true)
        val secondLease = second.taskLock(resource.toNIO, LauncherLocking.LockKind.Write)
        secondLease.close()
        second.close()
      }
    }

    test("latest-links-fall-back-to-most-recent-run-that-published-each-file") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        def manager(command: String) = new LauncherLockingImpl(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = command,
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )

        val first = manager("first")
        val firstProfilePath = os.Path(first.artifactPath((out / OutFiles.millProfile).toNIO))
        os.write.over(firstProfilePath, "first-profile")
        first.publishArtifacts()

        val second = manager("second")
        val secondChromePath = os.Path(second.artifactPath((out / OutFiles.millChromeProfile).toNIO))
        os.write.over(secondChromePath, "second-chrome")
        second.publishArtifacts()

        assert(os.read(out / OutFiles.millProfile) == "first-profile")
        assert(os.read(out / OutFiles.millChromeProfile) == "second-chrome")

        first.close()
        second.close()
      }
    }

    test("per-run-files-preserve-path-relative-to-out") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)

        val manager = new LauncherLockingImpl(
          out = out,
          daemonDir = out / OutFiles.millDaemon,
          activeCommandMessage = "test-command",
          launcherPid = 12345L,
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )

        val topLevelProfile =
          os.Path(manager.artifactPath((out / OutFiles.millProfile).toNIO))
        val metaBuildProfile =
          os.Path(manager.artifactPath((out / OutFiles.millBuild / OutFiles.millProfile).toNIO))

        assert(topLevelProfile != metaBuildProfile)
        assert(topLevelProfile.relativeTo(out).segments == Seq(
          "mill-run",
          manager.runId,
          OutFiles.millProfile
        ))
        assert(metaBuildProfile.relativeTo(out).segments == Seq(
          "mill-run",
          manager.runId,
          OutFiles.millBuild,
          OutFiles.millProfile
        ))

        os.write.over(topLevelProfile, "top-level-profile")
        os.write.over(metaBuildProfile, "meta-build-profile")
        manager.publishArtifacts()

        assert(os.isLink(out / OutFiles.millProfile))
        assert(os.isLink(out / OutFiles.millBuild / OutFiles.millProfile))
        assert(os.read(out / OutFiles.millProfile) == "top-level-profile")
        assert(os.read(out / OutFiles.millBuild / OutFiles.millProfile) == "meta-build-profile")

        manager.close()
      }
    }

    test("concurrent-managers-respect-fair-lock-order") {
      withTmpDir { tmpDir =>
        val out = tmpDir / "out"
        os.makeDir.all(out)
        val waitingBytes = new ByteArrayOutputStream()
        val waitingErr = new PrintStream(waitingBytes)

        def manager(command: String) = new LauncherLockingImpl(
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
        val resource = out / "fair-resource"
        val firstReadLease = firstReaderManager.taskLock(resource.toNIO, LauncherLocking.LockKind.Read)
        val writerAcquired = new CountDownLatch(1)
        val releaseWriter = new CountDownLatch(1)
        val secondReaderAcquired = new CountDownLatch(1)
        @volatile var writerLease: LauncherLocking.Lease = null
        @volatile var secondReadLease: LauncherLocking.Lease = null

        val writerThread = new Thread(() => {
          writerLease = writerManager.taskLock(resource.toNIO, LauncherLocking.LockKind.Write)
          writerAcquired.countDown()
          releaseWriter.await(5, TimeUnit.SECONDS)
          writerLease.close()
        })
        writerThread.start()

        assertEventually(waitingBytes.size() > 0)

        val secondReaderThread = new Thread(() => {
          secondReadLease = secondReaderManager.taskLock(resource.toNIO, LauncherLocking.LockKind.Read)
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
          new LauncherLockingImpl(
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
        val resource = out / "no-wait-fair-resource"
        val firstReadLease = firstReaderManager.taskLock(resource.toNIO, LauncherLocking.LockKind.Read)
        val writerAcquired = new CountDownLatch(1)
        val releaseWriter = new CountDownLatch(1)
        @volatile var writerLease: LauncherLocking.Lease = null

        val writerThread = new Thread(() => {
          writerLease = writerManager.taskLock(resource.toNIO, LauncherLocking.LockKind.Write)
          writerAcquired.countDown()
          releaseWriter.await(5, TimeUnit.SECONDS)
          writerLease.close()
        })
        writerThread.start()
        assertEventually(waitingBytes.size() > 0)

        val ex =
          try {
            noWaitReaderManager.taskLock(resource.toNIO, LauncherLocking.LockKind.Read)
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
