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
  }

  private def withTmpDir[T](body: os.Path => T): T = {
    val tmpDir = os.Path(Files.createTempDirectory("workspace-locking-tests"))
    try body(tmpDir)
    finally os.remove.all(tmpDir)
  }
}
