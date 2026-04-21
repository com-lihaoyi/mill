package mill.api.daemon

import mill.constants.{DaemonFiles, OutFiles}
import utest.*

import java.io.PrintStream
import java.nio.file.Files

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
          waitingErr = new PrintStream(System.err),
          noBuildLock = false,
          noWaitForBuildLock = false
        )

        val profilePath = os.Path(manager.profilePathJava((out / OutFiles.millProfile).toNIO))
        val chromePath =
          os.Path(manager.chromeProfilePathJava((out / OutFiles.millChromeProfile).toNIO))
        val consoleTailPath = os.Path(manager.consoleTailJava)

        os.write.over(consoleTailPath, "tail")
        os.write.over(profilePath, "profile")
        os.write.over(chromePath, "chrome")

        manager.acquireLocks(Seq.empty).close()

        val activeLink = out / OutFiles.millActive
        assert(os.exists(out / DaemonFiles.millConsoleTail))
        assert(os.exists(out / OutFiles.millProfile))
        assert(os.exists(out / OutFiles.millChromeProfile))
        assert(os.exists(activeLink))

        assert(Files.isSymbolicLink((out / OutFiles.millProfile).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millChromeProfile).toNIO))
        assert(Files.isSymbolicLink(activeLink.toNIO))
        assert(Files.readSymbolicLink((out / OutFiles.millProfile).toNIO).toString.startsWith("mill-run/"))
        assert(Files.readSymbolicLink((out / OutFiles.millChromeProfile).toNIO).toString.startsWith("mill-run/"))

        manager.close()

        assert(os.exists(out / DaemonFiles.millConsoleTail))
        assert(os.exists(out / OutFiles.millProfile))
        assert(os.exists(out / OutFiles.millChromeProfile))
        assert(!os.exists(activeLink))

        assert(os.read(out / OutFiles.millProfile) == "profile")
        assert(os.read(out / OutFiles.millChromeProfile) == "chrome")
        assert(Files.isSymbolicLink((out / OutFiles.millProfile).toNIO))
        assert(Files.isSymbolicLink((out / OutFiles.millChromeProfile).toNIO))
        assert(Files.readSymbolicLink((out / OutFiles.millProfile).toNIO).toString.startsWith("mill-run/"))
        assert(Files.readSymbolicLink((out / OutFiles.millChromeProfile).toNIO).toString.startsWith("mill-run/"))
      }
    }
  }

  private def withTmpDir[T](body: os.Path => T): T = {
    val tmpDir = os.Path(Files.createTempDirectory("workspace-locking-tests"))
    try body(tmpDir)
    finally os.remove.all(tmpDir)
  }
}
