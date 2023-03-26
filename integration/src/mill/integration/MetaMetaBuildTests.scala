package mill.integration

import utest._

import scala.util.matching.Regex

class MetaMetaBuildTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("meta-meta-build", fork, clientServer) {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    def assertLinePrefix(lines: Seq[String], prefix: String) = {
      assert(lines.exists(_.startsWith(prefix)))
    }
    def assertLineContains(lines: Seq[String], prefix: String) = {
      assert(lines.exists(_.contains(prefix)))
    }

    def mangleFile(p: os.Path, f: String => String) = os.write.over(p, f(os.read(p)))

    def runAssertSuccess() = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(res.outLines.contains("<h1>hello</h1><p>world</p>"))
    }

    // Cause various kinds of errors in various levels of build.sc meta-builds,
    // ensuring that the proper messages are reported in all cases.
    test("multiLevelErrorReporting") {
      test("parseError") {
        def causeParseError(p: os.Path) =
          mangleFile(p, _.replace("extends", "extendx"))

        def fixParseError(p: os.Path) =
          mangleFile(p, _.replace("extendx", "extends"))

        runAssertSuccess()

        causeParseError(workspaceRoot / "build.sc")

        evalStdoutAssert("foo.run"){ res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] 1 targets failed")
          assertLinePrefix(res.errLines, "[mill-build] millbuild.generateScriptSources build.sc")
        }

        causeParseError(workspaceRoot / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run"){ res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] 1 targets failed")
          assertLinePrefix(
            res.errLines,
            "[mill-build] [mill-build] millbuild.generateScriptSources mill-build/build.sc"
          )
        }

        causeParseError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] [mill-build] 1 targets failed")
          assertLinePrefix(
            res.errLines,
            "[mill-build] [mill-build] [mill-build] millbuild.generateScriptSources mill-build/mill-build/build.sc"
          )
        }

        fixParseError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run"){res =>

          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] 1 targets failed")
          assertLinePrefix(
            res.errLines,
            "[mill-build] [mill-build] millbuild.generateScriptSources mill-build/build.sc"
          )
        }

        fixParseError(workspaceRoot / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run"){ res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] 1 targets failed")
          assertLinePrefix(res.errLines, "[mill-build] millbuild.generateScriptSources build.sc")
        }

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

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] 1 targets failed")
          // Ensure the file path in the compile error is properly adjusted to point
          // at the original source file and not the generated file
          assertLineContains(res.errLines, s"$workspaceRoot/build.sc")
          assertLineContains(res.errLines, "not found: value doesnt")
        }

        causeCompileError(workspaceRoot / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] 1 targets failed")
          assertLineContains(res.errLines, s"$workspaceRoot/mill-build/build.sc")
          assertLineContains(res.errLines, "not found: value doesnt")
        }

        causeCompileError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] [mill-build] 1 targets failed")
          assertLineContains(res.errLines, s"$workspaceRoot/mill-build/mill-build/build.sc")
          assertLineContains(res.errLines, "not found: value doesnt")
        }

        fixCompileError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] 1 targets failed")
          assertLineContains(res.errLines, s"$workspaceRoot/mill-build/build.sc")
          assertLineContains(res.errLines, "not found: value doesnt")
        }

        fixCompileError(workspaceRoot / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] 1 targets failed")
          assertLineContains(res.errLines, s"$workspaceRoot/build.sc")
          assertLineContains(res.errLines, "not found: value doesnt")
        }

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

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "1 targets failed")
          assertLineContains(res.errLines, "foo.runClasspath java.lang.Exception: boom")
        }

        causeRuntimeError(workspaceRoot / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] 1 targets failed")
          assertLineContains(res.errLines, "build.sc")
          assertLineContains(res.errLines, "millbuild.runClasspath java.lang.Exception: boom")
        }

        causeRuntimeError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] [mill-build] 1 targets failed")
          assertLineContains(res.errLines, "build.sc")
          assertLineContains(res.errLines, "millbuild.runClasspath java.lang.Exception: boom")
        }

        fixRuntimeError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "[mill-build] 1 targets failed")
          assertLineContains(res.errLines, "build.sc")
          assertLineContains(res.errLines, "millbuild.runClasspath java.lang.Exception: boom")
        }

        fixRuntimeError(workspaceRoot / "mill-build" / "build.sc")

        evalStdoutAssert("foo.run") { res =>
          assert(res.isSuccess == false)
          assertLinePrefix(res.errLines, "1 targets failed")
          assertLineContains(res.errLines, "build.sc")
          assertLineContains(res.errLines, "foo.runClasspath java.lang.Exception: boom")
        }

        fixRuntimeError(workspaceRoot / "build.sc")

        runAssertSuccess()
      }
    }
  }
}
