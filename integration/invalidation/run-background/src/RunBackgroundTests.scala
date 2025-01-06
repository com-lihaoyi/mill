package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import java.io.RandomAccessFile
import utest.asserts.{RetryInterval, RetryMax}
import scala.concurrent.duration._
import utest._

object RunBackgroundTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(5000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  def probeLockAvailable(lock: os.Path) = {
    val raf = new RandomAccessFile(lock.toIO, "rw");
    val chan = raf.getChannel();
    chan.tryLock() match{
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
      eventually { !probeLockAvailable(lock) }
      os.write(stop, "")
      eventually { probeLockAvailable(lock) }
    }
    test("clean") - integrationTest { tester =>
      import tester._
      val lock = os.temp()
      val stop = os.temp()
      os.remove(stop)
      eval(("foo.runBackground", lock, stop))
      eventually { !probeLockAvailable(lock) }

      eval(("clean", "foo.runBackground"))
      eventually { probeLockAvailable(lock) }
    }
  }
}
