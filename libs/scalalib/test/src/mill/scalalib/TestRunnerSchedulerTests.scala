package mill.scalalib

import mill.testkit.UnitTester
import utest.*

object TestRunnerSchedulerTests extends TestSuite {
  import TestRunnerTestUtils.*

  override def tests: Tests = Tests {
    test("non-parallel queue scheduler uses one runner per group") - {
      UnitTester(testrunner, resourcePath, threads = Some(2)).scoped { eval =>
        val Right(result) = eval.apply(testrunner.queueDrift.testForked()).runtimeChecked

        assert(
          result.value.results.map(_.fullyQualifiedName).toSet == Set(
            "mill.scalalib.QueueDriftStart",
            "mill.scalalib.QueueDriftFiller"
          )
        )
        assert(
          os.exists(
            eval.outPath / "queueDrift" / "testForked.dest" / "test-classes" /
              "mill.scalalib.QueueDriftExtra"
          )
        )
      }
    }
  }
}
