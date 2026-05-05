package mill.integration

import ch.epfl.scala.bsp4j as b
import mill.constants.DaemonFiles
import mill.integration.BspServerTestUtil.*
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Concurrency tests that exercise the lock-blocking interaction between BSP
 * requests (running in the daemon) and CLI invocations against the same
 * shared output directory. They assert on the *fine-grained* lock messages
 * that surface to whichever side is waiting:
 *
 *   - CLI side: "blocked on <kind> lock '<task>' command 'BSP:<endpoint>' PID <bspPid>"
 *     appears in the launcher's stderr when BSP holds a task lock.
 *   - BSP side: the same shape with `command '<cliCommand>' PID <cliPid>`
 *     appears in the daemon-side BSP server stderr when CLI holds a task lock.
 *
 * The tests rely on `gated.compile` parking on a workspace-relative gate file
 * (see resources/build.mill) so the test runner can deterministically
 * sequence acquisition.
 */
object BspConcurrencyTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  private def gateFile(tester: IntegrationTester.Impl): os.Path =
    tester.workspacePath / "gated-sources-wait"

  private def enteredFile(tester: IntegrationTester.Impl): os.Path =
    tester.workspacePath / "gated-sources-entered"

  private def exclusiveGateFile(tester: IntegrationTester.Impl): os.Path =
    tester.workspacePath / "gated-exclusive-wait"

  private def exclusiveEnteredFile(tester: IntegrationTester.Impl): os.Path =
    tester.workspacePath / "gated-exclusive-entered"

  /** Read the launcher record store to find the active PID for a given command. */
  private def activeLauncherPid(
      tester: IntegrationTester.Impl,
      commandSubstring: String
  ): Option[Long] = {
    val millRun = tester.workspacePath / "out" / os.RelPath(DaemonFiles.millRun)
    if (!os.exists(millRun)) None
    else
      os.list(millRun).iterator
        .filter(os.isFile(_))
        .flatMap { path =>
          val json = ujson.read(os.read(path)).obj
          val cmd = json.get("command").map(_.str).getOrElse("")
          val pid = json.get("pid").map(_.num.toLong)
          Option.when(cmd.contains(commandSubstring))(pid).flatten
        }
        .toSeq
        .lastOption
  }

  private def awaitActiveLauncherPid(
      tester: IntegrationTester.Impl,
      commandSubstring: String
  ): Long = {
    var pid = Option.empty[Long]
    assertEventually {
      pid = activeLauncherPid(tester, commandSubstring)
      pid.nonEmpty
    }
    pid.get
  }

  def tests: Tests = Tests {

    test("bsp-holds-sources-lock-blocks-concurrent-cli-invocation") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      // Pre-arm the gate so `gated.sources` parks as soon as it enters.
      os.write.over(gateFile(tester), "")

      // Install BSP and capture BSP-side stderr for diagnostic logging.
      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      var cliLauncherOpt = Option.empty[mill.testkit.IntegrationTester.SpawnedProcess]
      try {
        val bspStderr = new ByteArrayOutputStream
        withBspServer(
          workspacePath,
          millTestSuiteEnv,
          bspLog = Some((bytes, len) => bspStderr.write(bytes, 0, len))
        ) { (buildServer, _) =>
          // Resolve target metadata first; this returns quickly because BSP
          // doesn't evaluate `sources` for `workspaceBuildTargets`.
          val targets = buildServer.workspaceBuildTargets().get(30, TimeUnit.SECONDS)
          val gatedTarget = targets.getTargets.asScala
            .find(_.getDisplayName == "gated").map(_.getId).getOrElse(
              throw new java.lang.AssertionError(
                s"BSP exposed no `gated` target. Targets: " +
                  targets.getTargets.asScala.map(_.getDisplayName)
              )
            )

          // Kick off a BSP compile that depends on `gated.sources` and parks on the gate.
          val bspCompileFuture =
            buildServer.buildTargetCompile(new b.CompileParams(Seq(gatedTarget).asJava))

          // Wait until the BSP-spawned compile is actually inside the gated body
          // (holding `gated.sources`), then capture the BSP launcher's PID.
          assertEventually(os.exists(enteredFile(tester)))
          val bspPid = awaitActiveLauncherPid(tester, "BSP:")

          // Now spawn a concurrent CLI invocation of the same task; it must
          // observe the exact fine-grained lock-wait message naming the BSP
          // command + PID and stay blocked while the BSP side holds the lock.
          val cliLauncher = spawn(("runGatedSources"))
          cliLauncherOpt = Some(cliLauncher)
          assertEventually {
            val errText = cliLauncher.err.text()
            (errText.contains("blocked on read lock 'gated.sources' command 'BSP:") ||
              errText.contains("blocked on write lock 'gated.sources' command 'BSP:")) &&
            errText.contains(s"PID $bspPid")
          }
          assert(cliLauncher.process.isAlive())

          // Release the gate; both sides should now proceed and complete.
          os.remove(gateFile(tester))
          cliLauncher.process.waitFor()
          val bspResult = bspCompileFuture.get(60, TimeUnit.SECONDS)

          assert(cliLauncher.process.exitCode() == 0)
          assert(bspResult.getStatusCode == b.StatusCode.OK)
        }
      } finally {
        if (os.exists(gateFile(tester))) os.remove(gateFile(tester))
        cliLauncherOpt.foreach { l =>
          if (l.process.isAlive()) l.process.waitFor(30000L)
        }
      }
    }

    test("cli-exclusive-command-blocks-concurrent-bsp-request") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      // Pre-arm the exclusive gate; the CLI command takes the workspace-level
      // write lock and parks while the gate file exists.
      os.write.over(exclusiveGateFile(tester), "")

      eval(
        ("--bsp-install", "--jobs", "1"),
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = true,
        env = Map("MILL_EXECUTABLE_PATH" -> tester.millExecutable.toString)
      )

      val cliLauncher = spawn(("runGatedExclusive"))
      try {
        assertEventually(os.exists(exclusiveEnteredFile(tester)))
        val cliPid = awaitActiveLauncherPid(tester, "runGatedExclusive")

        val bspStderr = new ByteArrayOutputStream
        withBspServer(
          workspacePath,
          millTestSuiteEnv,
          bspLog = Some((bytes, len) => bspStderr.write(bytes, 0, len))
        ) { (buildServer, _) =>
          // The CLI launcher holds the workspace 'exclusive' write lock, so the
          // first BSP request that needs the read side of that lock must block
          // and emit a fine-grained wait message into the daemon-side BSP stderr
          // naming the CLI command + PID.
          val workspaceTargetsFuture = buildServer.workspaceBuildTargets()

          assertEventually {
            val text = bspStderr.toString
            (text.contains("blocked on read lock 'exclusive' command 'runGatedExclusive'") ||
              text.contains(
                "blocked on write lock 'exclusive' command 'runGatedExclusive'"
              )) &&
            text.contains(s"PID $cliPid")
          }
          // The BSP RPC future must not have resolved yet — still parked on the lock.
          assert(!workspaceTargetsFuture.isDone)

          // Release the gate; CLI completes first, BSP unblocks, both succeed.
          os.remove(exclusiveGateFile(tester))
          cliLauncher.process.waitFor()
          val targets = workspaceTargetsFuture.get(60, TimeUnit.SECONDS)
          assert(cliLauncher.process.exitCode() == 0)
          assert(targets.getTargets.asScala.exists(_.getDisplayName == "gated"))
        }
      } finally {
        if (os.exists(exclusiveGateFile(tester))) os.remove(exclusiveGateFile(tester))
        if (cliLauncher.process.isAlive()) cliLauncher.process.waitFor(30000L)
      }
    }
  }
}
