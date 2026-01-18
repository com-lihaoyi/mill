package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*

import utest.*
import utest.asserts.{RetryMax, RetryInterval}

object SelectiveExecutionTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(120.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    test("default-command") - integrationTest { tester =>
      import tester.*

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
        import tester.*

        val cached = eval(
          ("selective.run", "{foo.fooCommand,bar.barCommand}"),
          check = false,
          stderr = os.Pipe
        )

        assert(cached.err.contains("`selective.run` can only be run after `selective.prepare`"))
      }
    }
    test("renamed-tasks") - integrationTest { tester =>
      import tester.*
      eval(("selective.prepare", "{foo,bar}._"), check = true)

      modifyFile(workspacePath / "build.mill", _.replace("fooTask", "fooTaskRenamed"))
      modifyFile(workspacePath / "build.mill", _.replace("barCommand", "barCommandRenamed"))

      val cached = eval(("selective.resolve", "{foo,bar}._"), stderr = os.Pipe)

      assert(
        cached.out.linesIterator.toList.sorted ==
          Seq("bar.barCommandRenamed", "foo.fooCommand", "foo.fooTaskRenamed")
      )
    }
    test("overrideSuper") - integrationTest { tester =>
      import tester.*
      eval(("selective.prepare", "qux.quxCommand"), check = true)

      modifyFile(
        workspacePath / "build.mill",
        _.replace("Computing quxCommand", "Computing quxCommand!")
      )

      val cached = eval(("selective.run", "qux.quxCommand"), stderr = os.Pipe)

      assert(
        cached.out.linesIterator.toList ==
          Seq("Computing quxCommand!")
      )
    }

    test("mill-version-change-triggers-full-rerun") - integrationTest { tester =>
      import tester.*

      // Prepare selective execution with multiple tasks
      eval(("selective.prepare", "{foo,bar}._"), check = true)

      // Without any changes, selective.resolve should return nothing
      val resolve1 = eval(("selective.resolve", "{foo,bar}._"), check = true)
      assert(resolve1.out == "")

      // Modify the millVersion in the metadata file to simulate a Mill version change
      val metadataPath = workspacePath / "out/mill-selective-execution.json"
      val metadata = ujson.read(os.read(metadataPath))
      val originalVersion = metadata("millVersion").str
      metadata("millVersion") = "0.0.0-old-version"
      os.write.over(metadataPath, ujson.write(metadata, indent = 2))

      // Now selective.resolve should return all tasks since the version changed
      val resolve2 = eval(("selective.resolve", "{foo,bar}._"), check = true)
      val resolvedTasks = resolve2.out.linesIterator.toList.sorted

      // All tasks should be invalidated
      assert(resolvedTasks.nonEmpty)
      assert(resolvedTasks.contains("foo.fooCommand"))
      assert(resolvedTasks.contains("bar.barCommand"))

      // Check that resolveTree shows the mill-version changed reason
      // with the full task tree nested underneath
      val resolveTree = eval(("selective.resolveTree", "{foo,bar}._"), check = true)

      // Normalize the mill version in the output for comparison
      val normalizedTree = resolveTree.out.linesIterator.toSeq.map { line =>
        line.replaceAll("<mill-version-changed:0\\.0\\.0-old-version->[^>]+>", "<mill-version-changed:OLD->NEW>")
      }

      assertGoldenLiteral(
        normalizedTree,
        Seq(
          "{",
          "  \"<mill-version-changed:OLD->NEW>\": {",
          "    \"foo.fooTask\": {",
          "      \"foo.fooCommand\": {}",
          "    },",
          "    \"bar.barTask\": {",
          "      \"bar.barCommand\": {",
          "        \"bar.barCommand2\": {}",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )
    }

    test("mill-jvm-version-change-triggers-full-rerun") - integrationTest { tester =>
      import tester.*

      // Prepare selective execution with multiple tasks
      eval(("selective.prepare", "{foo,bar}._"), check = true)

      // Without any changes, selective.resolve should return nothing
      val resolve1 = eval(("selective.resolve", "{foo,bar}._"), check = true)
      assert(resolve1.out == "")

      // Modify the millJvmVersion in the metadata file to simulate a JVM version change
      val metadataPath = workspacePath / "out/mill-selective-execution.json"
      val metadata = ujson.read(os.read(metadataPath))
      val originalJvmVersion = metadata("millJvmVersion").str
      metadata("millJvmVersion") = "1.0.0-old-jvm"
      os.write.over(metadataPath, ujson.write(metadata, indent = 2))

      // Now selective.resolve should return all tasks since the JVM version changed
      val resolve2 = eval(("selective.resolve", "{foo,bar}._"), check = true)
      val resolvedTasks = resolve2.out.linesIterator.toList.sorted

      // All tasks should be invalidated
      assert(resolvedTasks.nonEmpty)
      assert(resolvedTasks.contains("foo.fooCommand"))
      assert(resolvedTasks.contains("bar.barCommand"))

      // Check that resolveTree shows the mill-jvm-version changed reason
      val resolveTree = eval(("selective.resolveTree", "{foo,bar}._"), check = true)

      // Normalize the JVM version in the output for comparison
      val normalizedTree = resolveTree.out.linesIterator.toSeq.map { line =>
        line.replaceAll("<mill-jvm-version-changed:1\\.0\\.0-old-jvm->[^>]+>", "<mill-jvm-version-changed:OLD->NEW>")
      }

      assertGoldenLiteral(
        normalizedTree,
        Seq(
          "{",
          "  \"<mill-jvm-version-changed:OLD->NEW>\": {",
          "    \"foo.fooTask\": {",
          "      \"foo.fooCommand\": {}",
          "    },",
          "    \"bar.barTask\": {",
          "      \"bar.barCommand\": {",
          "        \"bar.barCommand2\": {}",
          "      }",
          "    }",
          "  }",
          "}"
        )
      )
    }
  }
}
