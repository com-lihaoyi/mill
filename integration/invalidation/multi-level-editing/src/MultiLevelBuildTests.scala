package mill.integration

import mill.testkit.IntegrationTestSuite

import mill.main.client.OutFiles._
import mill.runner.RunnerState
import utest._

import scala.util.matching.Regex

// Cause various kinds of changes - valid, parse errors, compile errors,
// runtime errors - in various levels of build.mill meta-builds, ensuring
// that the proper messages are reported, proper build classloaders are
// re-used or invalidated, and the proper files end up getting watched
// in all cases.
object MultiLevelBuildTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    def runAssertSuccess(expected: String) = {
      val res = eval("foo.run")
      assert(res.isSuccess == true)
      assert(res.out.contains(expected))
    }

    val fooPaths = Seq(
      workspacePath / "foo" / "compile-resources",
      workspacePath / "foo" / "resources",
      workspacePath / "foo" / "src"
    )
    val buildPaths = Seq(
      workspacePath / "build.mill",
      workspacePath / "mill-build" / "compile-resources",
      workspacePath / "mill-build" / "resources",
      workspacePath / "mill-build" / "src"
    )
    val buildPaths2 = Seq(
      workspacePath / "mill-build" / "build.mill",
      workspacePath / "mill-build" / "mill-build" / "compile-resources",
      workspacePath / "mill-build" / "mill-build" / "resources",
      workspacePath / "mill-build" / "mill-build" / "src"
    )
    val buildPaths3 = Seq(
      workspacePath / "mill-build" / "mill-build" / "build.mill",
      workspacePath / "mill-build" / "mill-build" / "mill-build" / "compile-resources",
      workspacePath / "mill-build" / "mill-build" / "mill-build" / "resources",
      workspacePath / "mill-build" / "mill-build" / "mill-build" / "src"
    )

    def loadFrames(n: Int) = {
      for (depth <- Range(0, n))
        yield {
          val path =
            workspacePath / "out" / Seq.fill(depth)(millBuild) / millRunnerState
          if (os.exists(path)) upickle.default.read[RunnerState.Frame.Logged](os.read(path)) -> path
          else RunnerState.Frame.Logged(Map(), Seq(), Seq(), None, Seq(), 0) -> path
        }
    }

    /**
     * Verify that each level of the multi-level build ends upcausing the
     * appropriate files to get watched
     */
    def checkWatchedFiles(expected0: Seq[os.Path]*) = {
      for ((expectedWatched0, (frame, path)) <- expected0.zip(loadFrames(expected0.length))) {
        val frameWatched = frame
          .evalWatched
          .map(_.path)
          .filter(_.startsWith(workspacePath))
          .filter(!_.segments.contains("mill-launcher"))
          .sorted

        val expectedWatched = expectedWatched0.sorted
        assert(frameWatched == expectedWatched)
      }
    }

    def evalCheckErr(expectedSnippets: String*) = {
      // Wipe out stale state files to make sure they don't get picked up when
      // Mill aborts early and fails to generate a new one
      os.walk(workspacePath / "out").filter(_.last == "mill-runner-state.json").foreach(
        os.remove(_)
      )

      val res = eval("foo.run")
      assert(res.isSuccess == false)
      // Prepend a "\n" to allow callsites to use "\n" to test for start of
      // line, even though the first line doesn't have a "\n" at the start
      val err = "\n" + res.err
      for (expected <- expectedSnippets) {
        assert(err.contains(expected))
      }
    }

    var savedClassLoaderIds = Seq.empty[Option[Int]]

    /**
     * Check whether the classloaders of the nested meta-builds are changing as
     * expected. `true` means a new classloader was created, `false` means
     * the previous classloader was re-used, `null` means there is no
     * classloader at that level
     */
    def checkChangedClassloaders(expectedChanged0: java.lang.Boolean*) = {
      val currentClassLoaderIds =
        for ((frame, path) <- loadFrames(expectedChanged0.length))
          yield frame.classLoaderIdentity

      val changed = currentClassLoaderIds
        .zipAll(savedClassLoaderIds, None, None)
        .map { case (cur, old) =>
          if (cur.isEmpty) null
          else cur != old
        }

      val expectedChanged =
        if (clientServerMode) expectedChanged0
        else expectedChanged0.map {
          case java.lang.Boolean.FALSE => true
          case n => n
        }

      assert(changed == expectedChanged)

      savedClassLoaderIds = currentClassLoaderIds
    }

    test("validEdits") {
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      // First run all classloaders are new, except level 0 running user code
      // which doesn't need generate a classloader which never changes
      checkChangedClassloaders(null, true, true, true)

      modifyFile(workspacePath / "foo" / "src" / "Example.scala", _.replace("!", "?"))
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      // Second run with no build changes, all classloaders are unchanged
      checkChangedClassloaders(null, false, false, false)

      modifyFile(workspacePath / "build.mill", _.replace("hello", "HELLO"))
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

      modifyFile(
        workspacePath / "mill-build" / "build.mill",
        _.replace("def scalatagsVersion = ", "def scalatagsVersion = \"changed-\" + ")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, false)

      modifyFile(
        workspacePath / "mill-build" / "mill-build" / "build.mill",
        _.replace("0.8.2", "0.12.0")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.12.0</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      modifyFile(
        workspacePath / "mill-build" / "mill-build" / "build.mill",
        _.replace("0.12.0", "0.8.2")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      modifyFile(
        workspacePath / "mill-build" / "build.mill",
        _.replace("def scalatagsVersion = \"changed-\" + ", "def scalatagsVersion = ")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, false)

      modifyFile(workspacePath / "build.mill", _.replace("HELLO", "hello"))
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

      modifyFile(workspacePath / "foo" / "src" / "Example.scala", _.replace("?", "!"))
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, false, false, false)
    }

    test("parseErrorEdits") {
      def causeParseError(p: os.Path) =
        modifyFile(p, _.replace("extends", "extendx"))

      def fixParseError(p: os.Path) =
        modifyFile(p, _.replace("extendx", "extends"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      causeParseError(workspacePath / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources build.mill"
      )
      checkWatchedFiles(Nil, buildPaths, Nil, Nil)
      // When one of the meta-builds still has parse errors, all classloaders
      // remain null, because none of the meta-builds can evaluate. Only once
      // all of them parse successfully do we get a new set of classloaders for
      // every level of the meta-build
      checkChangedClassloaders(null, null, null, null)

      fixParseError(workspacePath / "build.mill")
      causeParseError(workspacePath / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/build.mill"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, Nil)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(workspacePath / "mill-build" / "build.mill")
      causeParseError(workspacePath / "mill-build" / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/mill-build/build.mill"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(workspacePath / "mill-build" / "mill-build" / "build.mill")
      causeParseError(workspacePath / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/build.mill"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, Nil)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(workspacePath / "mill-build" / "build.mill")
      causeParseError(workspacePath / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources build.mill"
      )
      checkWatchedFiles(Nil, buildPaths, Nil, Nil)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(workspacePath / "build.mill")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)
    }

    test("compileErrorEdits") {
      def causeCompileError(p: os.Path) =
        modifyFile(p, _ + "\nimport doesnt.exist")

      def fixCompileError(p: os.Path) =
        modifyFile(p, _.replace("import doesnt.exist", ""))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      causeCompileError(workspacePath / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        // Ensure the file path in the compile error is properly adjusted to point
        // at the original source file and not the generated file
        (workspacePath / "build.mill").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, false, false)

      causeCompileError(workspacePath / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        (workspacePath / "mill-build" / "build.mill").toString,
        "not found: object doesnt"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, null, false)

      causeCompileError(workspacePath / "mill-build" / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        (workspacePath / "mill-build" / "mill-build" / "build.mill").toString,
        "not found: object doesnt"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)
      checkChangedClassloaders(null, null, null, null)

      fixCompileError(workspacePath / "mill-build" / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        (workspacePath / "mill-build" / "build.mill").toString,
        "not found: object doesnt"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, null, true)

      fixCompileError(workspacePath / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        (workspacePath / "build.mill").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, true, false)

      fixCompileError(workspacePath / "build.mill")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)
    }

    test("runtimeErrorEdits") {
      val runErrorSnippet = """{
                              |override def runClasspath = T{
                              |  throw new Exception("boom")
                              |  super.runClasspath()
                              |}""".stripMargin

      def causeRuntimeError(p: os.Path) =
        modifyFile(p, _.replaceFirst("\\{", runErrorSnippet))

      def fixRuntimeError(p: os.Path) =
        modifyFile(p, _.replaceFirst(Regex.quote(runErrorSnippet), "\\{"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      causeRuntimeError(workspacePath / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "foo.runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

      causeRuntimeError(workspacePath / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "build.mill",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, true, false)

      causeRuntimeError(workspacePath / "mill-build" / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "build.mill",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, null, true)

      fixRuntimeError(workspacePath / "mill-build" / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "build.mill",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, true, true)

      fixRuntimeError(workspacePath / "mill-build" / "build.mill")
      evalCheckErr(
        "\n1 targets failed",
        "build.mill",
        "foo.runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, false)

      fixRuntimeError(workspacePath / "build.mill")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

    }
  }
}
