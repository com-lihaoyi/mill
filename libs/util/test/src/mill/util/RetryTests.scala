package mill.util

import mill.util.Retry
import utest.*

object RetryTests extends TestSuite {
  val tests: Tests = Tests {
    test("fail") {
      var count = 0
      try {
        Retry(logger = Retry.printStreamLogger(System.err)) {
          count += 1
          throw new Exception("boom")
        }
      } catch {
        case ex =>
          assert(ex.getMessage == "boom")
      }

      assert(count == 6) // 1 original try + 5 retries
    }
    test("succeed") {
      var count = 0
      Retry(logger = Retry.printStreamLogger(System.err)) {
        count += 1
        if (count < 3) throw new Exception("boom")
      }
      assert(count == 3)
    }
    test("filter") {
      var count = 0
      try {
        Retry(
          logger = Retry.printStreamLogger(System.err),
          filter = {
            case (_, _: RuntimeException) => true
            case _ => false
          }
        ) {
          count += 1
          if (count < 3) throw new RuntimeException("boom")
          else throw new Exception("foo")
        }
      } catch {
        case e: Exception =>
          assert(e.getMessage == "foo")
      }
      assert(count == 3)
    }
    test("timeout") {
      test("fail") {
        var count = 0
        try {
          Retry(logger = Retry.printStreamLogger(System.err), timeoutMillis = 100) {
            count += 1
            Thread.sleep(1000)
          }
        } catch {
          case e: Exception =>
            assert(e.getMessage == "Future timed out after [100 milliseconds]")
        }

        assert(count == 6) // 1 original try + 5 retries
      }
      test("success") {
        var count = 0
        Retry(logger = Retry.printStreamLogger(System.err), timeoutMillis = 100) {
          count += 1
          if (count < 3) Thread.sleep(1000)
        }

        assert(count == 3)
      }

    }
  }
}
