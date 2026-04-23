package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*

import utest.*
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionWatchTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {

    test("changed-inputs") - retry(1) {
      integrationTest { tester =>
        import tester.*
        val spawned = spawn(("--watch", "{foo.fooCommand,bar.barCommand}"))

        assertEventually {
          spawned.out.text().contains("Computing fooCommand") &&
          spawned.out.text().contains("Computing barCommand")
        }

        // Make sure editing each individual input results in the corresponding downstream
        // command being re-run, and watches on both are maintained even if in a prior run
        // one set of tasks was ignored.
        spawned.clear()
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")
        assertEventually {
          !spawned.out.text().contains("Computing fooCommand") &&
          spawned.out.text().contains("Computing barCommand")
        }

        // Test for a bug where modifying the sources 2nd time would run tasks from both modules.
        spawned.clear()
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")
        assertEventually {
          !spawned.out.text().contains("Computing fooCommand") &&
          spawned.out.text().contains("Computing barCommand")
        }

        spawned.clear()
        modifyFile(workspacePath / "foo/foo.txt", _ + "!")
        assertEventually {
          spawned.out.text().contains("Computing fooCommand") &&
          !spawned.out.text().contains("Computing barCommand")
        }
      }
    }
    test("show-changed-inputs") - retry(1) {
      integrationTest { tester =>
        import tester.*
        val spawned = spawn(("--watch", "show", "{foo.fooCommand,bar.barCommand}"))

        assertEventually {
          spawned.err.text().contains("Computing fooCommand") &&
          spawned.err.text().contains("Computing barCommand")
        }

        spawned.clear()
        modifyFile(workspacePath / "bar/bar.txt", _ + "!")
        assertEventually {
          !spawned.err.text().contains("Computing fooCommand") &&
          spawned.err.text().contains("Computing barCommand")
        }

        spawned.clear()
        modifyFile(workspacePath / "foo/foo.txt", _ + "!")
        assertEventually {
          spawned.err.text().contains("Computing fooCommand") &&
          !spawned.err.text().contains("Computing barCommand")
        }
      }
    }

    test("changed-code") - retry(1) {
      integrationTest { tester =>
        import tester.*

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
          !spawned.out.text().contains("Computing fooCommand") &&
          spawned.out.text().contains("Computing barCommand")
        }

        // Check module body code changes correctly trigger downstream evaluation
        spawned.clear()
        modifyFile(
          workspacePath / "build.mill",
          _.replace("object foo extends Module {", "object foo extends Module { println(123)")
        )
        assertEventually {
          spawned.out.text().contains("Computing fooCommand") &&
          !spawned.out.text().contains("Computing barCommand")
        }
      }
    }

    // Make sure that if a task fail/skipped/aborted during `--watch`, next time we
    // run things selectively we run that task again to help ensure that the user
    // seeing no failures in the terminal really means there are no failures left
    test("rerun-failures") - retry(1) {
      integrationTest { tester =>
        import tester.*

        modifyFile(
          workspacePath / "build.mill",
          _.replace(
            "def fooCommand() = Task.Command {",
            "def fooCommand() = Task.Command { sys.error(\"boom foo\")"
          )
            .replace(
              "def barCommand() = Task.Command {",
              "def barCommand() = Task.Command { sys.error(\"boom bar\")"
            )
        )

        val spawned = spawn(("-j1", "--watch", "{foo.fooCommand,bar.barCommand}"))

        assertEventually { spawned.err.text().contains("boom") }

        spawned.clear()
        modifyFile(workspacePath / "build.mill", _.replace("sys.error(\"boom foo\")", ""))

        assertEventually {
          spawned.out.text().contains("Computing fooCommand") &&
          // Make sure `bar` re-runs here and blows up again, even though we didn't modify the
          // task, so the user is aware there is still a remaining failure after fixing foo
          spawned.err.text().contains("boom bar")
        }

        spawned.clear()
        modifyFile(workspacePath / "build.mill", _.replace("sys.error(\"boom bar\")", ""))

        assertEventually { spawned.out.text().contains("Computing barCommand") }
      }
    }
  }
}
