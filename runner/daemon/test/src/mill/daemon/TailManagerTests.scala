package mill.daemon

import mill.constants.DaemonFiles
import utest.*

import java.io.ByteArrayOutputStream

object TailManagerTests extends TestSuite {
  val tests = Tests {
    test("withOutErrDrainsTheFilesToTheClientBeforeHandingBackToTheDaemon") {
      val daemonDir = os.temp.dir(deleteOnExit = false)
      val newOut = ByteArrayOutputStream()
      val newErr = ByteArrayOutputStream()
      val manager = TailManager(daemonDir)
      try {
        // let the tailers' first pass run; content present at the first pass is ignored
        Thread.sleep(100)

        manager.withOutErr(newOut, newErr) {
          os.write.append(daemonDir / DaemonFiles.stderr, "last err line\n")
          os.write.append(daemonDir / DaemonFiles.stdout, "last out line\n")
        }

        // after the swap, further output belongs to the daemon's own streams, not the client
        os.write.append(daemonDir / DaemonFiles.stderr, "later err line\n")
        os.write.append(daemonDir / DaemonFiles.stdout, "later out line\n")
        Thread.sleep(100)
      } finally {
        manager.close()
      }
      {
        val expectedOut = s"last out line${System.lineSeparator()}"
        val expectedErr = s"last err line${System.lineSeparator()}"
        val o = newOut.toString()
        val e = newErr.toString()
        assert(o == expectedOut)
        assert(o == expectedErr)
      }
    }
  }
}
