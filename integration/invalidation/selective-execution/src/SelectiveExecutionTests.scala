package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration._

import utest._
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
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

object SelectiveExecutionChangedCodeTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
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

  }
}

object SelectiveExecutionWatchTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {

    test("watch") {
      test("changed-inputs") - retry(1) {
        integrationTest { tester =>
          import tester._
          val spawned = spawn(("--watch", "{foo.fooCommand,bar.barCommand}"))

          assertEventually {
            spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          // Make sure editing each individual input results in the corresponding downstream
          // command being re-run, and watches on both are maintained even if in a prior run
          // one set of tasks was ignored.
          spawned.clear()
          modifyFile(workspacePath / "bar/bar.txt", _ + "!")
          assertEventually {
            !spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          // Test for a bug where modifying the sources 2nd time would run tasks from both modules.
          spawned.clear()
          modifyFile(workspacePath / "bar/bar.txt", _ + "!")
          assertEventually {
            !spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          spawned.clear()
          modifyFile(workspacePath / "foo/foo.txt", _ + "!")
          assertEventually {
            spawned.out.text().contains(
              "Computing fooCommand"
            ) && !spawned.out.text().contains("Computing barCommand")
          }
        }
      }
      test("show-changed-inputs") - retry(1) {
        integrationTest { tester =>
          import tester._
          val spawned = spawn(("--watch", "show", "{foo.fooCommand,bar.barCommand}"))

          assertEventually {
            spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          spawned.clear()
          modifyFile(workspacePath / "bar/bar.txt", _ + "!")
          assertEventually {
            !spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          spawned.clear()
          modifyFile(workspacePath / "foo/foo.txt", _ + "!")
          assertEventually {
            spawned.out.text().contains(
              "Computing fooCommand"
            ) && !spawned.out.text().contains("Computing barCommand")
          }
        }
      }

      test("changed-code") - retry(1) {
        integrationTest { tester =>
          import tester._

          val spawned = spawn(("--watch", "{foo.fooCommand,bar.barCommand}"))

          assertEventually {
            spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          // Check method body code changes correctly trigger downstream evaluation
          spawned.clear()
          modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))
          assertEventually {
            !spawned.out.text().contains(
              "Computing fooCommand"
            ) && spawned.out.text().contains("Computing barCommand")
          }

          // Check module body code changes correctly trigger downstream evaluation
          spawned.clear()
          modifyFile(
            workspacePath / "build.mill",
            _.replace("object foo extends Module {", "object foo extends Module { println(123)")
          )
          assertEventually {
            spawned.out.text().contains(
              "Computing fooCommand"
            ) && !spawned.out.text().contains("Computing barCommand")
          }
        }
      }
    }
  }
}
