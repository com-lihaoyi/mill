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

        // Make sure editing each individual input results in the corresponding downstream
        // command being re-run, and watches on both are maintained even if in a prior run
        // one set of tasks was ignored.
        output0 = Nil
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")
        eventually {
          !output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        }

        output0 = Nil
        modifyFile(workspacePath / "foo/foo.txt", _ + "!")
        eventually {
          output.contains("Computing fooCommand") && !output.contains("Computing barCommand")
        }
      }
      test("show-changed-inputs") - integrationTest { tester =>
        import tester._
        @volatile var output0 = List.empty[String]
        def output = output0.mkString("\n")
        Future {
          eval(
            ("--watch", "show", "{foo.fooCommand,bar.barCommand}"),
            check = true,
            stderr = os.ProcessOutput.Readlines(line => output0 = output0 :+ line),
            stdout = os.ProcessOutput.Readlines(line => output0 = output0 :+ line)
          )
        }

        eventually {
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        }
        output0 = Nil
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")

        eventually {
          !output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        }

        output0 = Nil
        modifyFile(workspacePath / "foo/foo.txt", _ + "!")
        eventually {
          output.contains("Computing fooCommand") && !output.contains("Computing barCommand")
        }
      }

      test("changed-code") - integrationTest { tester =>
        import tester._

        @volatile var output0 = List.empty[String]
        def output = output0.mkString("\n")
        Future {
          eval(
            ("--watch", "{foo.fooCommand,bar.barCommand}"),
            check = true,
            stdout = os.ProcessOutput.Readlines { line => output0 = output0 :+ line },
            stderr = os.Inherit
          )
        }

        eventually {
          output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        }
        output0 = Nil

        // Check method body code changes correctly trigger downstream evaluation
        modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))

        eventually {
          !output.contains("Computing fooCommand") && output.contains("Computing barCommand")
        }
        output0 = Nil

        // Check module body code changes correctly trigger downstream evaluation
        modifyFile(
          workspacePath / "build.mill",
          _.replace("object foo extends Module {", "object foo extends Module { println(123)")
        )

        eventually {
          output.contains("Computing fooCommand") && !output.contains("Computing barCommand")
        }
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
    test("renamed-tasks") - integrationTest { tester =>
      import tester._
      eval(("selective.prepare", "{foo,bar}._"), check = true)

      modifyFile(workspacePath / "build.mill", _.replace("fooTask", "fooTaskRenamed"))
      modifyFile(workspacePath / "build.mill", _.replace("barCommand", "barCommandRenamed"))

      val cached = eval(("selective.resolve", "{foo,bar}._"), stderr = os.Pipe)

      assert(
        cached.out.linesIterator.toList ==
          Seq("bar.barCommandRenamed", "foo.fooCommand", "foo.fooTaskRenamed")
      )
    }
  }
}
