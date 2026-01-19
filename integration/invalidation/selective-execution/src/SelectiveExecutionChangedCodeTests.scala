package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*

import utest.*
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionChangedCodeTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("changed-code") - integrationTest { tester =>
      import tester.*

      // Check method body code changes correctly trigger downstream evaluation
      eval(
        ("selective.prepare", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )
      modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))
      val cached1 = eval(
        ("selective.run", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      assert(!cached1.out.contains("Computing fooCommand"))
      assert(cached1.out.contains("Computing barCommand"))

      // Check module body code changes correctly trigger downstream evaluation
      eval(
        ("selective.prepare", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )
      modifyFile(
        workspacePath / "build.mill",
        _.replace("object foo extends Module {", "object foo extends Module { println(123)")
      )
      val cached2 = eval(
        ("selective.run", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      assert(cached2.out.contains("Computing fooCommand"))
      assert(!cached2.out.contains("Computing barCommand"))
    }

    test("resolveTree-shows-code-changed") - integrationTest { tester =>
      import tester.*

      // Prepare selective execution
      eval(
        ("selective.prepare", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      // Make a code change to barHelper
      modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))

      // Check that resolveTree shows the code change reason
      val resolveTree = eval(
        ("selective.resolveTree", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )

      // The output should show the spanning tree nodes directly (method/call chain)
      // showing how the barHelper change propagates to barCommand, with the actual
      // task name "bar.barCommand" at the leaf
      assertGoldenLiteral(
        resolveTree.out.linesIterator.toSeq,
        List(
          "{",
          "  \"def build_.package_$bar$#barHelper(os.Path)java.lang.String\": {",
          "    \"call build_.package_$bar$#barHelper(os.Path)java.lang.String\": {",
          "      \"def build_.package_$bar$#barCommand$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "        \"call build_.package_$bar$!barCommand$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "          \"def build_.package_$bar$#barCommand()mill.api.Task$Command\": {",
          "            \"bar.barCommand\": {}",
          "          }",
          "        }",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )

      // Now test with barCommand2 which depends on barCommand
      // Both barCommand and barCommand2 should appear in the tree
      eval(
        ("selective.prepare", "bar.barCommand2"),
        check = true,
        stderr = os.Inherit
      )

      // Make the same code change to barHelper again
      modifyFile(workspacePath / "build.mill", _.replace("\"barHelper! \"", "\"barHelper!! \""))

      val resolveTree2 = eval(
        ("selective.resolveTree", "bar.barCommand2"),
        check = true,
        stderr = os.Inherit
      )

      // The tree should show the code change propagation to barCommand,
      // and then barCommand2 as a downstream task
      assertGoldenLiteral(
        resolveTree2.out.linesIterator.toSeq,
        List(
          "{",
          "  \"def build_.package_$bar$#barHelper(os.Path)java.lang.String\": {",
          "    \"call build_.package_$bar$#barHelper(os.Path)java.lang.String\": {",
          "      \"def build_.package_$bar$#barCommand$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "        \"call build_.package_$bar$!barCommand$$anonfun$1(scala.collection.immutable.Seq,mill.api.TaskCtx)mill.api.daemon.Result\": {",
          "          \"def build_.package_$bar$#barCommand()mill.api.Task$Command\": {",
          "            \"bar.barCommand\": {",
          "              \"bar.barCommand2\": {}",
          "            }",
          "          }",
          "        }",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )
    }

  }
}
