package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*

import utest.*
import utest.asserts.{RetryInterval, RetryMax}

object SelectiveExecutionSelectiveInputsTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("selective-inputs") - integrationTest { tester =>
      import tester.*

      eval(
        ("selective.prepare", "baz.bazCommand"),
        check = true,
        stderr = os.Inherit
      )

      modifyFile(workspacePath / "baz/baz-other.txt", _ + "!")

      val resolve = eval(("selective.resolve", "baz.bazCommand"), check = true)
      assert(resolve.out == "")

      val cached = eval(
        ("selective.run", "baz.bazCommand"),
        check = true,
        stderr = os.Inherit
      )
      assert(!cached.out.contains("Computing bazCommand"))

      modifyFile(workspacePath / "baz/baz.txt", _ + "!")

      val resolve2 = eval(("selective.resolve", "baz.bazCommand"), check = true)
      assert(resolve2.out == "baz.bazCommand")

      val rerun = eval(
        ("selective.run", "baz.bazCommand"),
        check = true,
        stderr = os.Inherit
      )
      assert(rerun.out.contains("Computing bazCommand"))
    }
  }
}
