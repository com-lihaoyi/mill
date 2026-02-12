package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*

import utest.*
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionChangedInputsTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("changed-inputs") - integrationTest { tester =>
      import tester.*

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
      import tester.*

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

    test("resolveTree-shows-input-changed") - integrationTest { tester =>
      import tester.*

      // Prepare selective execution with barCommand2 which depends on barCommand -> barTask
      eval(
        ("selective.prepare", "bar.barCommand2"),
        check = true,
        stderr = os.Inherit
      )

      // Modify the input file that barTask reads
      modifyFile(workspacePath / "bar/bar.txt", _ + "!")

      // Check that resolveTree shows the input change propagating through the task graph
      val resolveTree = eval(
        ("selective.resolveTree", "bar.barCommand2"),
        check = true,
        stderr = os.Inherit
      )

      // The tree should show barTask (the changed input) as the root,
      // with barCommand and barCommand2 as downstream tasks
      assertGoldenLiteral(
        resolveTree.out.linesIterator.toSeq,
        List(
          "{",
          "  \"bar.barTask\": {",
          "    \"bar.barCommand\": {",
          "      \"bar.barCommand2\": {}",
          "    }",
          "  }",
          "}"
        )
      )
    }

    test("resolveTree-prunes-nonselected-diamond-branch") - integrationTest { tester =>
      import tester.*

      eval(
        ("selective.prepare", "diamond.top"),
        check = true,
        stderr = os.Inherit
      )

      modifyFile(workspacePath / "diamond/diamond.txt", _ + "!")

      val resolveTree = eval(
        ("selective.resolveTree", "diamond.top"),
        check = true,
        stderr = os.Inherit
      )

      val output = resolveTree.out
      assert(output.contains("diamond.top"))

      val hasLeft = output.contains("diamond.left")
      val hasRight = output.contains("diamond.right")

      // The tree is a spanning forest: only the branch containing the selected task should remain.
      assert(hasLeft ^ hasRight)
    }
  }
}
