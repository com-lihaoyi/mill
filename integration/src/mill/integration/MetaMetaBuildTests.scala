package mill.integration

import utest._

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
    test("parseErrorReportingInEachLevel") {
      def causeParseError(p: os.Path) = {
        os.write.over(p, os.read(p).replace("extends", "extendx"))
      }

      def fixParseError(p: os.Path) = {
        os.write.over(p, os.read(p).replace("extendx", "extends"))
      }

      val (isSuccess1, out1, err1) = evalStdout("foo.run")
      assert(isSuccess1 == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(out1.contains("<h1>hello</h1><p>world</p>"))

      causeParseError(workspaceRoot / "build.sc")

      val (isSuccess2, out2, err2) = evalStdout("foo.run")
      assert(isSuccess2 == false)
      assertLinePrefix(err2, "[mill-build] 1 targets failed")
      assertLinePrefix(err2, "[mill-build] millbuild.generateScriptSources build.sc")

      causeParseError(workspaceRoot / "mill-build" / "build.sc")

      val (isSuccess3, out3, err3) = evalStdout("foo.run")
      assert(isSuccess3 == false)
      assertLinePrefix(err2, "[mill-build] [mill-build] 1 targets failed")
      assertLinePrefix(
        err2,
        "[mill-build] [mill-build] millbuild.generateScriptSources mill-build/build.sc"
      )

      causeParseError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

      val (isSuccess4, out4, err4) = evalStdout("foo.run")
      assert(isSuccess4 == false)
      assertLinePrefix(err2, "[mill-build] [mill-build] [mill-build] 1 targets failed")
      assertLinePrefix(
        err2,
        "[mill-build] [mill-build] [mill-build] millbuild.generateScriptSources mill-build/mill-build/build.sc"
      )

      fixParseError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

      val (isSuccess5, out5, err5) = evalStdout("foo.run")
      assert(isSuccess5 == false)
      assertLinePrefix(err2, "[mill-build] [mill-build] 1 targets failed")
      assertLinePrefix(
        err2,
        "[mill-build] [mill-build] millbuild.generateScriptSources mill-build/build.sc"
      )

      fixParseError(workspaceRoot / "mill-build" / "build.sc")

      val (isSuccess6, out6, err6) = evalStdout("foo.run")
      assert(isSuccess6 == false)
      assertLinePrefix(err2, "[mill-build] 1 targets failed")
      assertLinePrefix(err2, "[mill-build] millbuild.generateScriptSources build.sc")

      fixParseError(workspaceRoot / "build.sc")
      val (isSuccess7, out7, err7) = evalStdout("foo.run")

      assert(isSuccess7 == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(out7.contains("<h1>hello</h1><p>world</p>"))
    }

    test("compileErrorReportingInEachLevel") {
      def causeCompileError(p: os.Path) = {
        os.write.over(p, os.read(p) + "\nimport doesnt.exist")
      }

      def fixCompileError(p: os.Path) = {
        os.write.over(p, os.read(p).replace("import doesnt.exist", ""))
      }
      val (isSuccess1, out1, err1) = evalStdout("foo.run")
      assert(isSuccess1 == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(out1.contains("<h1>hello</h1><p>world</p>"))

      causeCompileError(workspaceRoot / "build.sc")

      val (isSuccess2, out2, err2) = evalStdout("foo.run")
      assert(isSuccess2 == false)
      assertLinePrefix(err2, "[mill-build] 1 targets failed")
      assertLineContains(err2, "not found: value doesnt")

      causeCompileError(workspaceRoot / "mill-build" / "build.sc")

      val (isSuccess3, out3, err3) = evalStdout("foo.run")
      assert(isSuccess3 == false)
      assertLinePrefix(err3, "[mill-build] [mill-build] 1 targets failed")
      assertLineContains(err3, "not found: value doesnt")



      causeCompileError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

      val (isSuccess4, out4, err4) = evalStdout("foo.run")
      assert(isSuccess4 == false)
      assertLinePrefix(err4, "[mill-build] [mill-build] [mill-build] 1 targets failed")
      assertLineContains(err4, "not found: value doesnt")

      fixCompileError(workspaceRoot / "mill-build" / "mill-build" / "build.sc")

      val (isSuccess5, out5, err5) = evalStdout("foo.run")
      assert(isSuccess5 == false)
      assertLinePrefix(
        err5,
        "[mill-build] [mill-build] 1 targets failed"
      )
      assertLineContains(err5, "not found: value doesnt")

      fixCompileError(workspaceRoot / "mill-build" / "build.sc")

      val (isSuccess6, out6, err6) = evalStdout("foo.run")
      assert(isSuccess6 == false)
      assertLinePrefix(err6, "[mill-build] 1 targets failed")
      assertLineContains(err6, "not found: value doesnt")

      fixCompileError(workspaceRoot / "build.sc")
      val (isSuccess7, out7, err7) = evalStdout("foo.run")

      assert(isSuccess7 == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(out7.contains("<h1>hello</h1><p>world</p>"))
    }
  }
}
