package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import utest._
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("changed-inputs") - integrationTest { tester =>
      import tester._

      eval(("selective.prepare", "{foo.fooCommand,bar.barCommand}"), check = true)
      modifyFile(workspacePath / "bar/bar.txt", _ + "!")

      val resolve = eval(("selective.resolve", "{foo.fooCommand,bar.barCommand}"), check = true)
      assert(resolve.out == "bar.barCommand")

      val cached =
        eval(
          ("selective.run", "{foo.fooCommand,bar.barCommand}"),
          check = true,
          stderr = os.Inherit
        )

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }
    test("default-command") - integrationTest { tester =>
      import tester._

      eval(("selective.prepare", "bar"), check = true)

      val resolve = eval(("selective.resolve", "bar"), check = true)
      assert(resolve.out == "")

      modifyFile(workspacePath / "bar/bar.txt", _ + "!")
      val resolve2 = eval(("selective.resolve", "bar"), check = true)
      assert(resolve2.out != "")

      val cached = eval(("selective.run", "bar"), check = true, stderr = os.Inherit)

      assert(!cached.out.contains("Computing fooCommand"))
      assert(cached.out.contains("Computing barCommand"))
    }
    test("changed-code") - integrationTest { tester =>
      import tester._

      // Check method body code changes correctly trigger downstream evaluation
      eval(
        ("selective.prepare", "{foo.fooCommand,bar.barCommand}"),
        check = true,
        stderr = os.Inherit
      )
      modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))
      val cached1 =
        eval(
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
      val cached2 =
        eval(
          ("selective.run", "{foo.fooCommand,bar.barCommand}"),
          check = true,
          stderr = os.Inherit
        )

      assert(cached2.out.contains("Computing fooCommand"))
      assert(!cached2.out.contains("Computing barCommand"))
    }

    test("watch") {
      test("changed-inputs") - integrationTest { tester =>
        import tester._
        @volatile var output0 = List.empty[String]
        def output = output0.mkString("\n")
        Future {
          eval(
            ("--watch", "{foo.fooCommand,bar.barCommand}"),
            check = true,
            stdout = os.ProcessOutput.Readlines(line => output0 = output0 :+ line),
            stderr = os.Inherit
          )
        }

        eventually(
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
        output0 = Nil
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")
        eventually(
          !output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
        eventually(
          !output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
      }
      test("show-changed-inputs") - integrationTest { tester =>
        import tester._
        @volatile var output0 = List.empty[String]
        def output = output0.mkString("\n")
        Future {
          eval(
            ("--watch", "show", "{foo.fooCommand,bar.barCommand}"),
            check = true,
            stderr = os.ProcessOutput.Readlines(line => output0 = output0 :+ line)
          )
        }

        eventually(
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
        output0 = Nil
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")
        // For now, selective execution doesn't work with `show`, and always runs all provided
        // tasks. This is necessary because we need all specified tasks to be run in order to
        // get their value to render as JSON at the end of `show`
        eventually(
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
        eventually(
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
      }

      test("changed-code") - integrationTest { tester =>
        import tester._

        @volatile var output0 = List.empty[String]
        def output = output0.mkString("\n")
        Future {
          eval(
            ("--watch", "{foo.fooCommand,bar.barCommand}"),
            check = true,
            stdout = os.ProcessOutput.Readlines(line => output0 = output0 :+ line),
            stderr = os.Inherit
          )
        }

        eventually(
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
        output0 = Nil

        // Check method body code changes correctly trigger downstream evaluation
        modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))

        eventually(
          !output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        )
        output0 = Nil

        // Check module body code changes correctly trigger downstream evaluation
        modifyFile(
          workspacePath / "build.mill",
          _.replace("object foo extends Module {", "object foo extends Module { println(123)")
        )

        eventually(
          output.contains("Computing fooCommand") && !output.contains("Computing barCommand")
        )
      }
    }
    test("failures") {
      test("missing-prepare") - integrationTest { tester =>
        import tester._

        val cached = eval(
          ("selective.run", "{foo.fooCommand,bar.barCommand}"),
          check = false,
          stderr = os.Pipe
        )

        assert(cached.err.contains("`selective.run` can only be run after `selective.prepare`"))
      }
    }
  }
}
