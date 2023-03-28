package mill.integration

import mill.runner.RunnerState
import utest._

import scala.util.matching.Regex

// Cause various kinds of changes - valid, parse errors, compile errors,
// runtime errors - in various levels of build.sc meta-builds, ensuring
// that the proper messages are reported and the proper files end up
// getting watched in all cases.
class MultiLevelBuildTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("multi-level-build", fork, clientServer) {
  val tests = Tests {
    val wsRoot = initWorkspace()

    def mangleFile(p: os.Path, f: String => String) = os.write.over(p, f(os.read(p)))

    def runAssertSuccess(expected: String) = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(res.out.contains(expected))
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
      wsRoot / "mill-build" / "mill-build" / "src",
    )
    val buildPaths3 = Seq(
      wsRoot / "mill-build" / "mill-build" / "build.sc",
      wsRoot / "mill-build" / "mill-build" / "mill-build" / "compile-resources",
      wsRoot / "mill-build" / "mill-build" / "mill-build" / "resources",
      wsRoot / "mill-build" / "mill-build" / "mill-build" / "src"
    )

    def checkWatchedFiles(expected0: Seq[os.Path]*) = {
      for((expectedWatched0, depth) <- expected0.zipWithIndex){
        val frame = upickle.default.read[RunnerState.Frame.Logged](
          os.read(wsRoot / "out" / Seq.fill(depth)("mill-build") / "mill-runner-state.json")
        )

        if (frame.classLoaderId != null) pprint.log((depth, frame.classLoaderId.identityHashCode))
        pprint.log(frame.workerCache.mapValues(_.identityHashCode))
        val frameWatched = frame.watched.map(_.path).sorted
        val expectedWatched = expectedWatched0.sorted
        assert(frameWatched == expectedWatched)
      }
    }

    def evalCheckErr(expected: String*) = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == false)
      // Prepend a "\n" to allow callsites to use "\n" to test for start of
      // line, even though the first line doesn't have a "\n" at the start
      val err = "\n" + res.err
      for (e <- expected) {
        assert(err.contains(e))
      }
    }

    test("validEdits"){
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)

      mangleFile(wsRoot / "foo"  / "src" / "Example.scala", _.replace("!", "?"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>?")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(wsRoot / "build.sc", _.replace("hello", "HELLO"))
//
//      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>0.8.2</p>?")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(
//        wsRoot / "mill-build" / "build.sc",
//        _.replace("def scalatagsVersion = ", "def scalatagsVersion = \"changed-\" + ")
//      )
//
//      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.8.2</p>?")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(
//        wsRoot / "mill-build" / "mill-build" / "build.sc",
//        _.replace("0.8.2", "0.12.0")
//      )
//
//      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.12.0</p>?")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(
//        wsRoot / "mill-build" / "mill-build" / "build.sc",
//        _.replace("0.12.0", "0.8.2")
//      )
//
//      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>changed-0.8.2</p>?")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(
//        wsRoot / "mill-build" / "build.sc",
//        _.replace("def scalatagsVersion = \"changed-\" + ", "def scalatagsVersion = ")
//      )
//
//      runAssertSuccess("<h1>HELLO</h1><p>world</p><p>0.8.2</p>?")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(wsRoot / "build.sc", _.replace("HELLO", "hello"))
//
//      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>?")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
//
//      mangleFile(wsRoot / "foo"  / "src" / "Example.scala", _.replace("?", "!"))
//
//      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
//      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
    }

    test("parseErrorEdits") {
      def causeParseError(p: os.Path) =
        mangleFile(p, _.replace("extends", "extendx"))

      def fixParseError(p: os.Path) =
        mangleFile(p, _.replace("extendx", "extends"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)

      causeParseError(wsRoot / "build.sc")

      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources build.sc"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)

      causeParseError(wsRoot / "mill-build" / "build.sc")

      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/build.sc"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)

      causeParseError(wsRoot / "mill-build" / "mill-build" / "build.sc")

      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/mill-build/build.sc"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)

      fixParseError(wsRoot / "mill-build" / "mill-build" / "build.sc")

      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources mill-build/build.sc"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)

      fixParseError(wsRoot / "mill-build" / "build.sc")

      evalCheckErr(
        "\n1 targets failed",
        "\ngenerateScriptSources build.sc"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)

      fixParseError(wsRoot / "build.sc")

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
    }

    test("compileErrorEdits") {
      def causeCompileError(p: os.Path) =
        mangleFile(p, _ + "\nimport doesnt.exist")

      def fixCompileError(p: os.Path) =
        mangleFile(p, _.replace("import doesnt.exist", ""))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)

      causeCompileError(wsRoot / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        // Ensure the file path in the compile error is properly adjusted to point
        // at the original source file and not the generated file
        s"$wsRoot/build.sc",
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)

      causeCompileError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        s"$wsRoot/mill-build/build.sc",
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)

      causeCompileError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        s"$wsRoot/mill-build/mill-build/build.sc",
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)

      fixCompileError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        s"$wsRoot/mill-build/build.sc",
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)

      fixCompileError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        s"$wsRoot/build.sc",
        "not found: value doesnt"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)

      fixCompileError(wsRoot / "build.sc")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)
    }

    test("runtimeErrorEdits") {
      val runErrorSnippet = """{ override def runClasspath = T{ (throw new Exception("boom")): Seq[PathRef] }"""

      def causeRuntimeError(p: os.Path) =
        mangleFile(p, _.replaceFirst("\\{", runErrorSnippet))

      def fixRuntimeError(p: os.Path) =
        mangleFile(p, _.replaceFirst(Regex.quote(runErrorSnippet), "\\{"))

      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)

      causeRuntimeError(wsRoot / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "foo.runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)

      causeRuntimeError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)

      causeRuntimeError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, Nil, Nil, buildPaths3)

      fixRuntimeError(wsRoot / "mill-build" / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, Nil, buildPaths2, buildPaths3)

      fixRuntimeError(wsRoot / "mill-build" / "build.sc")
      evalCheckErr(
        "\n1 targets failed",
        "build.sc",
        "foo.runClasspath java.lang.Exception: boom"
      )
      checkWatchedFiles(Nil, buildPaths, buildPaths2, buildPaths3)

      fixRuntimeError(wsRoot / "build.sc")
      runAssertSuccess("<h1>hello</h1><p>world</p><p>0.8.2</p>!")
      checkWatchedFiles(fooPaths, buildPaths, buildPaths2, buildPaths3)

    }
  }
}
