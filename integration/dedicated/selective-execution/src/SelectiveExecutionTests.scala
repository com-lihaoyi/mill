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
          Seq(
            "bar.barCommandRenamed",
            "bar.barCommandRenamed2",
            "foo.fooCommand",
            "foo.fooTaskRenamed"
          )
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
        line.replaceAll(
          "mill-version-changed:0\\.0\\.0-old-version->[^\"]+",
          "mill-version-changed:OLD->NEW"
        )
      }

      // Version changes produce flat structure - all tasks are direct children of the
      // version change node since they all share the same root cause
      assertGoldenLiteral(
        normalizedTree,
        Seq(
          "{",
          "  \"mill-version-changed:OLD->NEW\": {",
          "    \"bar.barCommand\": {},",
          "    \"bar.barCommand2\": {},",
          "    \"foo.fooCommand\": {},",
          "    \"foo.fooTask\": {}",
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
        line.replaceAll(
          "mill-jvm-version-changed:1\\.0\\.0-old-jvm->[^\"]+",
          "mill-jvm-version-changed:OLD->NEW"
        )
      }

      // JVM version changes produce flat structure - all tasks are direct children of the
      // version change node since they all share the same root cause
      assertGoldenLiteral(
        normalizedTree,
        Seq(
          "{",
          "  \"mill-jvm-version-changed:OLD->NEW\": {",
          "    \"bar.barCommand\": {},",
          "    \"bar.barCommand2\": {},",
          "    \"foo.fooCommand\": {},",
          "    \"foo.fooTask\": {}",
          "  }",
          "}"
        )
      )
    }

    test("classpath-change-triggers-full-rerun") - integrationTest { tester =>
      import tester.*

      // Prepare selective execution with multiple tasks
      eval(("selective.prepare", "{foo,bar}._"), check = true)

      // Without any changes, selective.resolve should return nothing
      val resolve1 = eval(("selective.resolve", "{foo,bar}._"), check = true)
      assert(resolve1.out == "")

      // Add a dependency to build.mill to change the classpath
      // This simulates adding a third-party library which should invalidate all tasks
      // since codeSignatures doesn't track third-party library changes
      modifyFile(
        workspacePath / "build.mill",
        content => """//| mvnDeps: ["com.lihaoyi::scalatags:0.13.1"]
""" + content
      )

      // Now selective.resolve should return all tasks since the classpath changed
      val resolve2 = eval(("selective.resolve", "{foo,bar}._"), check = true)
      val resolvedTasks = resolve2.out.linesIterator.toList.sorted

      // All tasks should be invalidated due to classpath change
      assert(resolvedTasks.nonEmpty)
      assert(resolvedTasks.contains("foo.fooCommand"))
      assert(resolvedTasks.contains("bar.barCommand"))

      // Check that resolveTree shows the classpath changed reason
      val resolveTree = eval(("selective.resolveTree", "{foo,bar}._"), check = true)

      // Normalize the hash values in the output for comparison
      val normalizedTree = resolveTree.out.linesIterator.toSeq.map { line =>
        line.replaceAll(
          "classpath-changed:-?\\d+->-?\\d+",
          "classpath-changed:OLD->NEW"
        )
      }

      // Classpath changes produce flat structure - all tasks are direct children of the
      // classpath change node since they all share the same root cause
      assertGoldenLiteral(
        normalizedTree,
        Seq(
          "{",
          "  \"classpath-changed:OLD->NEW\": {",
          "    \"bar.barCommand\": {},",
          "    \"bar.barCommand2\": {},",
          "    \"foo.fooCommand\": {},",
          "    \"foo.fooTask\": {}",
          "  }",
          "}"
        )
      )
    }
  }
}
