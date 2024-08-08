package mill.integration

import mill.runner.RunnerState
import utest._

import scala.util.matching.Regex

// Cause various kinds of changes - valid, parse errors, compile errors,
// runtime errors - in various levels of build.sc meta-builds, ensuring
// that the proper messages are reported, proper build classloaders are
// re-used or invalidated, and the proper files end up getting watched
// in all cases.
object MultiLevelBuildTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    val wsRoot = initWorkspace()

    def runAssertSuccess(expected: String) = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == true)
      assert(res.out.contains(expected))
    }

    val fooPaths = Seq(
      wsRoot / "foo" / "compile-resources",
      wsRoot / "foo" / "resources",
      wsRoot / "foo" / "src"
    )
    val buildPaths = Seq(
      wsRoot / "build.sc",
      wsRoot / "mill-build" / "compile-resources",
      wsRoot / "mill-build" / "resources",
      wsRoot / "mill-build" / "src"
    )
    val buildPaths2 = Seq(
      wsRoot / "mill-build" / "build.sc",
      wsRoot / "mill-build" / "mill-build" / "compile-resources",
      wsRoot / "mill-build" / "mill-build" / "resources",
      wsRoot / "mill-build" / "mill-build" / "src"
    )
    val buildPaths3 = Seq(
      wsRoot / "mill-build" / "mill-build" / "build.sc",
      wsRoot / "mill-build" / "mill-build" / "mill-build" / "compile-resources",
      wsRoot / "mill-build" / "mill-build" / "mill-build" / "resources",
      wsRoot / "mill-build" / "mill-build" / "mill-build" / "src"
    )

    def loadFrames(n: Int) = {
      for (depth <- Range(0, n))
        yield {
          val path = wsRoot / "out" / Seq.fill(depth)("mill-build") / "mill-runner-state.json"
          if (os.exists(path)) upickle.default.read[RunnerState.Frame.Logged](os.read(path)) -> path
          else RunnerState.Frame.Logged(Map(), Seq(), Seq(), Map(), None, Seq(), 0) -> path
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
          .filter(_.startsWith(wsRoot))
          .filter(!_.segments.contains("mill-launcher"))
          .sorted

        val expectedWatched = expectedWatched0.sorted
        assert(frameWatched == expectedWatched)
      }
    }

    def evalCheckErr(expectedSnippets: String*) = {
      // Wipe out stale state files to make sure they don't get picked up when
      // Mill aborts early and fails to generate a new one
      os.walk(wsRoot / "out").filter(_.last == "mill-runner-state.json").foreach(os.remove(_))

      val res = evalStdout("foo.run")
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
        if (integrationTestMode != "fork") expectedChanged0
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

      mangleFile(wsRoot / "foo" / "src" / "Example.scala", _.replace("!", "?"))
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      // Second run with no build changes, all classloaders are unchanged
      checkChangedClassloaders(null, false, false, false)

      mangleFile(wsRoot / "build.sc", _.replace("hello", "HELLO"))
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

      mangleFile(
        wsRoot / "mill-build" / "build.sc",
        _.replace("def scalatagsVersion = ", "def scalatagsVersion = \"changed-\" + ")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, false)

      mangleFile(
        wsRoot / "mill-build" / "mill-build" / "build.sc",
        _.replace("0.8.2", "0.12.0")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.12.0</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      mangleFile(
        wsRoot / "mill-build" / "mill-build" / "build.sc",
        _.replace("0.12.0", "0.8.2")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      mangleFile(
        wsRoot / "mill-build" / "build.sc",
        _.replace("def scalatagsVersion = \"changed-\" + ", "def scalatagsVersion = ")
      )
      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, false)

      mangleFile(wsRoot / "build.sc", _.replace("HELLO", "hello"))
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

      mangleFile(wsRoot / "foo" / "src" / "Example.scala", _.replace("?", "!"))
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, false, false, false)
    }

    test("parseErrorEdits") {
      def causeParseError(p: os.Path) =
        mangleFile(p, _.replace("extends", "extendx"))

      def fixParseError(p: os.Path) =
        mangleFile(p, _.replace("extendx", "extends"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      causeParseError(wsRoot / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources build.sc"
      )
      checkWatchedFiles(Nil, buildPaths, Nil, Nil)
      // When one of the meta-builds still has parse errors, all classloaders
      // remain null, because none of the meta-builds can evaluate. Only once
      // all of them parse successfully do we get a new set of classloaders for
      // every level of the meta-build
      checkChangedClassloaders(null, null, null, null)

      fixParseError(wsRoot / "build.sc")
      causeParseError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/build.sc"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, Nil)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(wsRoot / "mill-build" / "build.sc")
      causeParseError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/mill-build/build.sc"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      causeParseError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/build.sc"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, Nil)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(wsRoot / "mill-build" / "build.sc")
      causeParseError(wsRoot / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources build.sc"
      )
      checkWatchedFiles(Nil, buildPaths, Nil, Nil)
      checkChangedClassloaders(null, null, null, null)

      fixParseError(wsRoot / "build.sc")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)
    }

    test("compileErrorEdits") {
      def causeCompileError(p: os.Path) =
        mangleFile(p, _ + "\nimport doesnt.exist")

      def fixCompileError(p: os.Path) =
        mangleFile(p, _.replace("import doesnt.exist", ""))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      causeCompileError(wsRoot / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        // Ensure the file path in the compile error is properly adjusted to point
        // at the original source file and not the generated file
        (wsRoot / "build.sc").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, false, false)

      causeCompileError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        (wsRoot / "mill-build" / "build.sc").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, null, false)

      causeCompileError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        (wsRoot / "mill-build" / "mill-build" / "build.sc").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)
      checkChangedClassloaders(null, null, null, null)

      fixCompileError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        (wsRoot / "mill-build" / "build.sc").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, null, true)

      fixCompileError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        (wsRoot / "build.sc").toString,
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, true, false)

      fixCompileError(wsRoot / "build.sc")
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
        mangleFile(p, _.replaceFirst("\\{", runErrorSnippet))

      def fixRuntimeError(p: os.Path) =
        mangleFile(p, _.replaceFirst(Regex.quote(runErrorSnippet), "\\{"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, true)

      causeRuntimeError(wsRoot / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "foo.runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

      causeRuntimeError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, true, false)

      causeRuntimeError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, null, true)

      fixRuntimeError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, null, true, true)

      fixRuntimeError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "foo.runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, true, false)

      fixRuntimeError(wsRoot / "build.sc")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
      checkChangedClassloaders(null, true, false, false)

    }
  }
}
