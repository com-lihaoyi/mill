package mill.integration

import utest._

import scala.util.matching.Regex

class MetaMetaBuildTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("meta-meta-build", fork, clientServer) {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    def mangleFile(p: os.Path, f: String => String) = os.write.over(p, f(os.read(p)))

    def runAssertSuccess() = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(res.outLines.contains("<h1>hello</h1><p>world</p>"))
    }

    // Cause various kinds of errors - parse, compile, & runtime - in various
    // levels of build.sc meta-builds, ensuring that the proper messages are
    // reported in all cases.
    test("multiLevelErrorReporting") {
      def evalCheckErr(expected: String*) = {
        val res = evalStdout("foo.run")
        assert(res.isSuccess == false)
        val err = res.errLines.map("\n"+_).mkString
        for(e <- expected){
          assert(err.contains(e))
        }
      }

      test("parseError") {
        def causeParseError(p: os.Path) =
          mangleFile(p, _.replace("extends", "extendx"))

        def fixParseError(p: os.Path) =
          mangleFile(p, _.replace("extendx", "extends"))

        runAssertSuccess()

        causeParseError(workspaceRoot / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "\ngenerateScriptSources build.sc"
        )

        causeParseError(workspaceRoot / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "\ngenerateScriptSources mill-build/build.sc"
        )

        causeParseError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "\ngenerateScriptSources mill-build/mill-build/build.sc"
        )

        fixParseError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "\ngenerateScriptSources mill-build/build.sc"
        )

        fixParseError(workspaceRoot / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "\ngenerateScriptSources build.sc"
        )

        fixParseError(workspaceRoot / "build.sc")

        runAssertSuccess()
      }

      test("compileError") {
        def causeCompileError(p: os.Path) =
          mangleFile(p, _ + "\nimport doesnt.exist")

        def fixCompileError(p: os.Path) =
          mangleFile(p, _.replace("import doesnt.exist", ""))

        runAssertSuccess()

        causeCompileError(workspaceRoot / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          // Ensure the file path in the compile error is properly adjusted to point
          // at the original source file and not the generated file
          s"$workspaceRoot/build.sc",
          "not found: value doesnt"
        )

        causeCompileError(workspaceRoot / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          s"$workspaceRoot/mill-build/build.sc",
          "not found: value doesnt"
        )

        causeCompileError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          s"$workspaceRoot/mill-build/mill-build/build.sc",
          "not found: value doesnt"
        )

        fixCompileError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          s"$workspaceRoot/mill-build/build.sc",
          "not found: value doesnt"
        )

        fixCompileError(workspaceRoot / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          s"$workspaceRoot/build.sc",
          "not found: value doesnt"
        )

        fixCompileError(workspaceRoot / "build.sc")

        runAssertSuccess()
      }

      test("runtimeError") {
        val runErrorSnippet = """{ override def runClasspath = T{ (throw new Exception("boom")): Seq[PathRef] }"""

        def causeRuntimeError(p: os.Path) =
          mangleFile(p, _.replaceFirst("\\{", runErrorSnippet))

        def fixRuntimeError(p: os.Path) =
          mangleFile(p, _.replaceFirst(Regex.quote(runErrorSnippet), "\\{"))

        runAssertSuccess()

        causeRuntimeError(workspaceRoot / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "foo.runClasspath java.lang.Exception: boom"
        )

        causeRuntimeError(workspaceRoot / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "build.sc",
          "runClasspath java.lang.Exception: boom"
        )

        causeRuntimeError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "build.sc",
          "runClasspath java.lang.Exception: boom"
        )

        fixRuntimeError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "build.sc",
          "runClasspath java.lang.Exception: boom"
        )

        fixRuntimeError(workspaceRoot / "mill-build" / "build.sc")

        evalCheckErr(
          "\n1 targets failed",
          "build.sc",
          "foo.runClasspath java.lang.Exception: boom"
        )

        fixRuntimeError(workspaceRoot / "build.sc")

        runAssertSuccess()
      }
    }
  }
}
