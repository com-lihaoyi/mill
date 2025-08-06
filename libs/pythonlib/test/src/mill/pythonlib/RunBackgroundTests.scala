package mill.pythonlib

import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.*
import mill.client.lock.Lock
import mill.api.Discover

object RunBackgroundTests extends TestSuite {

  object HelloWorldPython extends TestRootModule {
    object foo extends PythonModule {
      override def mainScript = Task.Source("src/foo.py")
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "run-background"
  def tests: Tests = Tests {
    test("runBackground") {
      UnitTester(HelloWorldPython, resourcePath).scoped { eval =>

        val lockedFile = os.temp()
        val Right(_) =
          eval.apply(HelloWorldPython.foo.runBackground(Args(lockedFile))): @unchecked
        val maxSleep = 20000
        val now1 = System.currentTimeMillis()
        val lock = Lock.file(lockedFile.toString())

        def sleepIfTimeAvailable(error: String) = {
          Thread.sleep(100)
          if (System.currentTimeMillis() - now1 > maxSleep) throw new Exception(error)
        }

        Thread.sleep(1000) // Make sure that the file remains locked even after a significant sleep

        while (lock.probe()) sleepIfTimeAvailable("File never locked by python subprocess")

        os.remove.all(eval.outPath / "foo/runBackground.dest")

        while (!lock.probe()) {
          sleepIfTimeAvailable("File never unlocked after runBackground.dest removed")
        }
      }
    }
  }
}
