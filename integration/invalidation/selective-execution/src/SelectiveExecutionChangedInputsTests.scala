package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration._

import utest._
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionChangedInputsTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("changed-inputs") - integrationTest { tester =>
      import tester._

      eval(
        ("selective.prepare", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      // no op
      val noOp = eval(
        ("selective.run", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      assert(!noOp.out.contains("Computing fooCommand"))
      assert(!noOp.out.contains("Computing barCommand"))

      // After one input changed
      modifyFile(workspacePath / "bar/bar.txt", _ + "!")

      val resolve = eval(("selective.resolve", "{foo.fooCommand,bar.barCommand}"), check = true)
      assert(resolve.out == "bar.barCommand")

      val cached = eval(
        ("selective.run", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))

      // zero out the `mill-selective-execution.json` file to run all tasks
      os.write.over(workspacePath / "out/mill-selective-execution.json", "")
      val runAll = eval(
        ("selective.run", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      assert(runAll.out.contains("Computing fooCommand"))
      assert(runAll.out.contains("Computing barCommand"))
    }

    test("changed-inputs-generic") - integrationTest { tester =>
      // Make sure you can run `selective.prepare` on a broader set of tasks than
      // `selective.resolve` or `selective.run` and thingsstill work
      import tester._

      // `selective.prepare` defaults to `__` if no selector is passed
      eval(("selective.prepare"), check = true)
      modifyFile(workspacePath / "bar/bar.txt", _ + "!")

      val resolve = eval(("selective.resolve", "bar.barCommand"), check = true)
      assert(resolve.out == "bar.barCommand")
      val resolve2 = eval(("selective.resolve", "foo.fooCommand"), check = true)
      assert(resolve2.out == "")

      val cached = eval(
        ("selective.run", "bar.barCommand"),
        check = true,
        stderr = os.Inherit
      )

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }
  }
}
