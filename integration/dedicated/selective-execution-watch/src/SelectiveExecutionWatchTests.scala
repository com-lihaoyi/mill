package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*

import utest.*
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionWatchTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)

  private def watchUnavailable(spawned: mill.testkit.IntegrationTester.SpawnedProcess): Boolean = {
    val output = spawned.out.text() + spawned.err.text()
    output.contains("FSEventStreamStart returned false") ||
    output.contains("can't set up watch, no file system changes detected")
  }

  private def stopIfWatchUnavailable(spawned: mill.testkit.IntegrationTester.SpawnedProcess): Boolean =
    if (watchUnavailable(spawned)) {
      spawned.process.destroy(recursive = false)
      spawned.process.waitFor()
      true
    } else false

  private def changedInputs(tester: mill.testkit.IntegrationTester): Unit = {
    import tester.*
    val spawned = spawn(("--watch", "{foo.fooCommand,bar.barCommand}"))

    assertEventually {
      watchUnavailable(spawned) ||
      spawned.out.text().contains("Computing fooCommand") &&
      spawned.out.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "bar/bar.txt", _ + "!")
    assertEventually {
      watchUnavailable(spawned) ||
      !spawned.out.text().contains("Computing fooCommand") &&
      spawned.out.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "bar/bar.txt", _ + "!")
    assertEventually {
      watchUnavailable(spawned) ||
      !spawned.out.text().contains("Computing fooCommand") &&
      spawned.out.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "foo/foo.txt", _ + "!")
    assertEventually {
      watchUnavailable(spawned) ||
      spawned.out.text().contains("Computing fooCommand") &&
      !spawned.out.text().contains("Computing barCommand")
    }
  }

  private def showChangedInputs(tester: mill.testkit.IntegrationTester): Unit = {
    import tester.*
    val spawned = spawn(("--watch", "show", "{foo.fooCommand,bar.barCommand}"))

    assertEventually {
      watchUnavailable(spawned) ||
      spawned.err.text().contains("Computing fooCommand") &&
      spawned.err.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "bar/bar.txt", _ + "!")
    assertEventually {
      watchUnavailable(spawned) ||
      !spawned.err.text().contains("Computing fooCommand") &&
      spawned.err.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "foo/foo.txt", _ + "!")
    assertEventually {
      watchUnavailable(spawned) ||
      spawned.err.text().contains("Computing fooCommand") &&
      !spawned.err.text().contains("Computing barCommand")
    }
  }

  private def changedCode(tester: mill.testkit.IntegrationTester): Unit = {
    import tester.*

    val spawned = spawn(("--watch", "{foo.fooCommand,bar.barCommand}"))

    assertEventually {
      watchUnavailable(spawned) ||
      spawned.out.text().contains("Computing fooCommand") &&
      spawned.out.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "build.mill", _.replace("\"barHelper \"", "\"barHelper! \""))
    assertEventually {
      watchUnavailable(spawned) ||
      !spawned.out.text().contains("Computing fooCommand") &&
      spawned.out.text().contains("Computing barCommand")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(
      workspacePath / "build.mill",
      _.replace("object foo extends Module {", "object foo extends Module { println(123)")
    )
    assertEventually {
      watchUnavailable(spawned) ||
      spawned.out.text().contains("Computing fooCommand") &&
      !spawned.out.text().contains("Computing barCommand")
    }
  }

  private def rerunFailures(tester: mill.testkit.IntegrationTester): Unit = {
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

    assertEventually { watchUnavailable(spawned) || spawned.err.text().contains("boom") }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "build.mill", _.replace("sys.error(\"boom foo\")", ""))

    assertEventually {
      watchUnavailable(spawned) ||
      spawned.out.text().contains("Computing fooCommand") &&
      spawned.err.text().contains("boom bar")
    }
    if (stopIfWatchUnavailable(spawned)) return

    spawned.clear()
    modifyFile(workspacePath / "build.mill", _.replace("sys.error(\"boom bar\")", ""))

    assertEventually { watchUnavailable(spawned) || spawned.out.text().contains("Computing barCommand") }
  }
  val tests: Tests = Tests {

    test("changed-inputs") - retry(1) {
      integrationTest(changedInputs)
    }
    test("show-changed-inputs") - retry(1) {
      integrationTest(showChangedInputs)
    }

    test("changed-code") - retry(1) {
      integrationTest(changedCode)
    }

    // Make sure that if a task fail/skipped/aborted during `--watch`, next time we
    // run things selectively we run that task again to help ensure that the user
    // seeing no failures in the terminal really means there are no failures left
    test("rerun-failures") - retry(1) {
      integrationTest(rerunFailures)
    }
  }
}
