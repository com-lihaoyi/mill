package mill.scripts

import utest.framework.TestPath
import utest.*

object MillVersionFrontmatterTests extends TestSuite {
  private val millVersion = "1.0.0-RC1"

  val tests: Tests = Tests {
    def doTest(
        frontmatter: String,
        expectedVersion: Option[String] = Some(millVersion),
        buildFile: String = "build.mill"
    )(using testValue: TestPath): Unit = {
      val wd = os.pwd / testValue.value
      os.makeDir.all(wd)
      os.write(wd / buildFile, frontmatter)

      // If that particular version is not downloaded to the cache, stdout will be polluted by the download messages.
      // Thus, we run our own task to print the version.
      val cmd = millCmd ++ Seq("version")
      println(s"Running $cmd in $wd")
      val res = os.call(
        cmd,
        cwd = wd,
        env = Map("MILL_TEST_DRY_RUN_LAUNCHER_SCRIPT" -> "1"),
        stderr = os.Pipe,
        check = false
      )
      val output = res.out.text().trim

      if (res.exitCode != 0) {
        throw new IllegalStateException(s"exitCode != 0 (actual = ${res.exitCode}")
      }

      expectedVersion match {
        case Some(expected) => assert(output.contains(s"/$expected/"))
        case None => assert(output.contains(s"/SNAPSHOT/"))
      }
    }

    test("noFrontmatter") - doTest("", expectedVersion = None)

    test("onFirstLine") - doTest(s"""//| mill-version: $millVersion""")

    test("onSecondLine") - doTest(
      s"""
         |//| mill-version: $millVersion
         |""".stripMargin
    )

    test("keyQuotedWithSingleQuote") - doTest(s"""//| 'mill-version': $millVersion""")

    test("keyQuotedWithDoubleQuote") - doTest(s"""//| "mill-version": $millVersion""")

    test("valueQuotedWithSingleQuote") - doTest(s"""//| mill-version: '$millVersion'""")

    test("valueQuotedWithDoubleQuote") - doTest(s"""//| mill-version: "$millVersion"""")

    test("keyAndValueQuotedWithSingleQuote") - doTest(s"""//| 'mill-version': '$millVersion'""")

    test("keyAndValueQuotedWithDoubleQuote") - doTest(s"""//| "mill-version": "$millVersion"""")

    test("keyQuotedWithSingleQuoteAndValueQuotedWithDoubleQuote") - doTest(
      s"""//| 'mill-version': "$millVersion""""
    )

    test("keyQuotedWithDoubleQuoteAndValueQuotedWithSingleQuote") - doTest(
      s"""//| "mill-version": '$millVersion'"""
    )

    test("withCommentAfterTheBuildHeader") - doTest(s"""//| mill-version: $millVersion # comment""")

    test("yaml") - {
      test("noFrontmatter") - doTest("", expectedVersion = None, buildFile = "build.mill.yaml")

      test("onFirstLine") - doTest(
        s"""mill-version: $millVersion""",
        buildFile = "build.mill.yaml"
      )

      test("onSecondLine") - doTest(
        s"""
           |mill-version: $millVersion
           |""".stripMargin,
        buildFile = "build.mill.yaml"
      )

      test("valueQuotedWithSingleQuote") - doTest(
        s"""mill-version: '$millVersion'""",
        buildFile = "build.mill.yaml"
      )

      test("valueQuotedWithDoubleQuote") - doTest(
        s"""mill-version: "$millVersion"""",
        buildFile = "build.mill.yaml"
      )

      test("withCommentAfterTheVersion") - doTest(
        s"""mill-version: $millVersion # comment""",
        buildFile = "build.mill.yaml"
      )
    }
  }
}
