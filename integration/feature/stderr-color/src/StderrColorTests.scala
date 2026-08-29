package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object StderrColorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("stdoutRedirectionKeepsStderrColor") {
      if (!scala.util.Properties.isWin) {
        integrationTest { tester =>
          checkStderrColor(tester, daemon = true)
          checkStderrColor(tester, daemon = false)
        }
      }
    }
  }

  private def checkStderrColor(
      tester: mill.testkit.IntegrationTester,
      daemon: Boolean
  ): Unit = {
    val prepared = tester.proc(Seq("resolve", "_"))
    val baseCommand0 = prepared.cmd.value.iterator.map(_.toString).toVector
      .filterNot(_ == "--no-daemon")
    val tickerIndex = baseCommand0.indexOf("--ticker")
    val baseCommand = baseCommand0.updated(tickerIndex + 1, "true")
    val command =
      if (daemon) baseCommand
      else baseCommand.patch(1, Seq("--no-daemon"), 0)

    val transcript = os.temp(prefix = s"mill-stderr-color-$daemon-", suffix = ".log")
    val stdout = os.temp(prefix = s"mill-stdout-$daemon-", suffix = ".log")
    val wrapper = os.temp(prefix = s"mill-stderr-color-$daemon-", suffix = ".sh")
    os.write.over(
      wrapper,
      s"#!/bin/sh\n${command.map(shellQuote).mkString(" ")} >${shellQuote(stdout.toString)}\n"
    )
    os.perms.set(wrapper, "rwxr-xr-x")

    val scriptCommand =
      if (scala.util.Properties.isMac)
        Seq("script", "-q", transcript.toString, wrapper.toString)
      else
        Seq("script", "-q", "-e", "-c", wrapper.toString, transcript.toString)

    val result = os.proc(scriptCommand).call(
      cwd = tester.workspacePath,
      env = (prepared.env - "NO_COLOR" - "FORCE_COLOR") + ("TERM" -> "xterm-256color"),
      stdout = os.Pipe,
      stderr = os.Pipe,
      check = false,
      propagateEnv = false
    )
    val terminalOutput = os.read.bytes(transcript)

    assert(result.exitCode == 0)
    assert(terminalOutput.contains(0x1b.toByte))
  }

  private def shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
}
