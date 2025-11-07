package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*
import utest._
import utest.asserts._

object ConcurrentInterruptShutdownTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(10000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)
  // Ensure that you can quickly cycle through startup and shutdown without failures.
  val tests: Tests = Tests {
    test("interrupt-blocked") - integrationTest { tester =>
      import tester.*
      @volatile var output1 = List.empty[String]
      @volatile var output2 = List.empty[String]
      val pipe1 = new mill.internal.PipeStreams()
      val pipe2 = new mill.internal.PipeStreams()
      assert(tester.daemonMode)
      val launcher1 = prepEval(
        ("waitForExists", "--fileName", "file1.txt"),
        stdout = os.ProcessOutput.Readlines { line =>
          println("thread 1 OUT " + line)
          output1 = output1 :+ line
        },
        stderr = os.ProcessOutput.Readlines { line =>
          println("thread 1 ERR " + line)
          output1 = output1 :+ line
        },
        stdin = pipe1.input
      ).spawn()

      assertEventually(output1.contains("Waiting on file1.txt"))
      val launcher2 = prepEval(
        ("runNow", "--text", "i am cow"),
        stdout = os.ProcessOutput.Readlines { line =>
          println("thread 2 OUT " + line)
          output2 = output2 :+ line
        },
        stderr = os.ProcessOutput.Readlines { line =>
          println("thread 2 ERR " + line)
          output2 = output2 :+ line
        },
        stdin = pipe2.input
      ).spawn()

      assertEventually(
        output2.contains(
          "Another Mill process is running 'waitForExists --fileName file1.txt', waiting for it to be done..."
        )
      )
      launcher2.destroy()
      assertEventually(!launcher2.isAlive())
      os.write(workspacePath / "file1.txt", "Hello world")
      assertEventually(output1.contains("Found file1.txt containing Hello world"))
      assert(!output2.contains("Hello i am cow"))
    }

    test("interrupt-active") - integrationTest { tester =>
      import tester.*
      @volatile var output1 = List.empty[String]
      @volatile var output2 = List.empty[String]
      val pipe1 = new mill.internal.PipeStreams()
      val pipe2 = new mill.internal.PipeStreams()
      assert(tester.daemonMode)
      val launcher1 = prepEval(
        ("waitForExists", "--fileName", "file1.txt"),
        stdout = os.ProcessOutput.Readlines { line =>
          println("thread 1 OUT " + line)
          output1 = output1 :+ line
        },
        stderr = os.ProcessOutput.Readlines { line =>
          println("thread 1 ERR " + line)
          output1 = output1 :+ line
        },
        stdin = pipe1.input
      ).spawn()

      assertEventually(output1.contains("Waiting on file1.txt"))
      val launcher2 = prepEval(
        ("runNow", "--text", "i am cow"),
        stdout = os.ProcessOutput.Readlines { line =>
          println("thread 2 OUT " + line)
          output2 = output2 :+ line
        },
        stderr = os.ProcessOutput.Readlines { line =>
          println("thread 2 ERR " + line)
          output2 = output2 :+ line
        },
        stdin = pipe2.input
      ).spawn()

      assertEventually(
        output2.contains(
          "Another Mill process is running 'waitForExists --fileName file1.txt', waiting for it to be done..."
        )
      )
      launcher1.destroy()
      assertEventually(!launcher1.isAlive())
      assert(!output1.exists(_.contains("Found")))
      assertEventually(output2.contains("Hello i am cow"))
    }
  }
}
