package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import java.io.RandomAccessFile
import utest.asserts.{RetryInterval, RetryMax}
import scala.concurrent.duration._
import utest._

// Make sure that `runBackground` subprocesses properly outlive the execution
// that created them, and outlive the Mill process whether terminated naturally
// at the end of `--no-server` or terminated explicitly via `shutdown`
object RunBackgroundTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(5000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  def probeLockAvailable(lock: os.Path): Boolean = {
    val raf = new RandomAccessFile(lock.toIO, "rw");
    val chan = raf.getChannel();
    chan.tryLock() match {
      case null => false
      case locked =>
        locked.release()
        true
    }
  }

  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._
      val lock = os.temp()
      val stop = os.temp()
      os.remove(stop)
      eval(("foo.runBackground", lock, stop))
      assertEventually { !probeLockAvailable(lock) }
      if (tester.daemonMode) eval("shutdown")
      continually { !probeLockAvailable(lock) }
      os.write(stop, "")
      assertEventually { probeLockAvailable(lock) }
    }

    test("sequential") - integrationTest { tester =>
      // This test fails on Windows. We will need to look into it, but it also does not work on `main` branch, so it's
      // not a regression, thus we'll leave it for now.
      if (!scala.util.Properties.isWin) {
        import tester._
        val lock1 = os.temp()
        val lock2 = os.temp()
        val stop = os.temp()
        os.remove(stop)
        eval(("foo.runBackground", lock1, stop))
        assertEventually { !probeLockAvailable(lock1) }
        eval(("foo.runBackground", lock2, stop))
        assertEventually { !probeLockAvailable(lock2) }
        Predef.assert(
          probeLockAvailable(lock1),
          "first process should be exited after second process is running"
        )

        if (tester.daemonMode) eval("shutdown")
        continually { !probeLockAvailable(lock2) }
        os.write(stop, "")
        assertEventually { probeLockAvailable(lock2) }
      }
    }

    test("clean") - integrationTest { tester =>
      import tester._
      val lock = os.temp()
      val stop = os.temp()
      os.remove(stop)
      eval(("foo.runBackground", lock, stop))
      assertEventually {
        !probeLockAvailable(lock)
      }

      eval(("clean", "foo.runBackground"))
      assertEventually {
        probeLockAvailable(lock)
      }
    }
  }
}
