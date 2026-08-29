package mill.launcher

import utest.*

object MillProcessLauncherTests extends TestSuite {
  def tests: Tests = Tests {
    test("stdoutAndStderrAreDetectedIndependently") {
      test("stdoutRedirected") {
        val onlyStderrIsTerminal = (fileDescriptor: Int) => if (fileDescriptor == 2) 1 else 0

        assert(MillProcessLauncher.stderrIsTerminal(onlyStderrIsTerminal))
      }

      test("stderrRedirected") {
        val onlyStdoutIsTerminal = (fileDescriptor: Int) => if (fileDescriptor == 1) 1 else 0

        assert(!MillProcessLauncher.stderrIsTerminal(onlyStdoutIsTerminal))
      }
    }
  }
}
