package mill.integration

import mill.constants.{DaemonFiles, OutFiles}
import mill.testkit.UtestIntegrationTestSuite
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object OutputDirectoryLockTests extends UtestIntegrationTestSuite {

  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  private def activeLauncherPid(workspacePath: os.Path, command: String): Option[Long] = {
    val millRun =
      workspacePath / "out" / os.RelPath(DaemonFiles.millRun)
    if (!os.exists(millRun)) None
    else {
      os.list(millRun).iterator
        .filter(os.isFile(_))
        .flatMap { path =>
          val json = ujson.read(os.read(path)).obj
          val fileCommand = json.get("command").map(_.str)
          val filePid = json.get("pid").map(_.num.toLong)
          Option.when(fileCommand.contains(command))(filePid).flatten
        }
        .toSeq
        .lastOption
    }
  }

  private def awaitActiveLauncherPid(workspacePath: os.Path, command: String): Long = {
    var pid = Option.empty[Long]
    assertEventually {
      pid = activeLauncherPid(workspacePath, command)
      pid.nonEmpty
    }
    pid.get
  }

  private def blockedLine(command: String, pid: Long, taskName: String): String =
    s"blocked taking read lock on '$taskName' held by PID $pid ($command)"

  def tests: Tests = Tests {
    test("taskLocks") - integrationTest { tester =>
      import tester.*
      val signalFile1 = workspacePath / "do-wait-1"
      val signalFile2 = workspacePath / "do-wait-2"
      val blocker = spawn(("blockWhileExists", "--path", signalFile1))

      // Wait for blocking task to write signal file, to indicate it has begun
      assertEventually { os.exists(signalFile1) }

      if (tester.daemonMode) {
        eval(("hello"), check = true)

        assertEventually {
          os.isLink(workspacePath / "out" / DaemonFiles.millConsoleTail) &&
          os.isLink(workspacePath / "out" / OutFiles.millProfile) &&
          os.isLink(workspacePath / "out" / OutFiles.millChromeProfile) &&
          os.isLink(workspacePath / "out" / OutFiles.millDependencyTree) &&
          os.isLink(workspacePath / "out" / OutFiles.millInvalidationTree)
        }
        val blockerPid = awaitActiveLauncherPid(
          workspacePath,
          s"blockWhileExists --path $signalFile1"
        )

        val noWaitRes = eval(
          ("--no-wait-for-build-lock", "blockWhileExists", "--path", signalFile2)
        )
        assert(
          noWaitRes.err.contains(
            blockedLine(
              s"blockWhileExists --path $signalFile1",
              blockerPid,
              "blockWhileExists"
            )
          )
        )

        val spawnedWaitingRes = spawn(("blockWhileExists", "--path", signalFile2))

        assertEventually {
          val stderrText = spawnedWaitingRes.err.text()
          spawnedWaitingRes.process.isAlive() &&
          !os.exists(signalFile2) &&
          stderrText.contains(
            blockedLine(
              s"blockWhileExists --path $signalFile1",
              blockerPid,
              "blockWhileExists"
            )
          )
        }

        os.remove(signalFile1)
        blocker.process.waitFor()
        assertEventually { os.exists(signalFile2) }
        os.remove(signalFile2)
        spawnedWaitingRes.process.waitFor()
        assert(!spawnedWaitingRes.process.isAlive())
      } else {
        val waitingCompleteFile = workspacePath / "waitingCompleteFile"
        val spawnedWaitingRes = spawn(("writeMarker", "--path", waitingCompleteFile))

        assertEventually {
          val stderrText = spawnedWaitingRes.err.text()
          stderrText.contains("Another Mill process with PID ") &&
          stderrText.contains(
            s" is running 'blockWhileExists --path $signalFile1', waiting for it to be done..."
          )
        }

        assert(spawnedWaitingRes.process.isAlive())
        assert(!os.exists(waitingCompleteFile))
        os.remove(signalFile1)
        blocker.process.waitFor()
        spawnedWaitingRes.process.waitFor()
        assert(os.exists(waitingCompleteFile))
      }
    }

    test("daemonAndNoDaemonProcessesExcludeEachOther") - integrationTest { tester =>
      import tester.*
      val signalFile1 = workspacePath / "daemon-process-lock-1"
      val signalFile2 = workspacePath / "daemon-process-lock-2"

      if (tester.daemonMode) {
        val daemonBlocker = spawn(("blockWhileExists", "--path", signalFile1))
        assertEventually { os.exists(signalFile1) }

        val noDaemonRejected = eval(
          ("--no-daemon", "--no-wait-for-build-lock", "writeMarker", "--path", signalFile2)
        )
        val stderrText = noDaemonRejected.err
        assert(!noDaemonRejected.isSuccess)
        assert(stderrText.contains("Another Mill process with PID "))
        assert(!os.exists(signalFile2))

        os.remove(signalFile1)
        daemonBlocker.process.waitFor()
      }
    }
  }
}
