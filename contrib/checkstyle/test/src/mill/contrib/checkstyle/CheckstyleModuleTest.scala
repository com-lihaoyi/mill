package mill
package contrib.checkstyle

import mainargs.Leftover
import mill.api.Result
import mill.scalalib.{JavaModule, ScalaModule}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object CheckstyleModuleTest extends TestSuite {

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))

  def testCheckstyle(
      module: TestBaseModule with CheckstyleModule,
      modulePath: os.Path,
      expectedViolations: Seq[String],
      args: CheckstyleArgs
  ): Boolean = {
    val eval = UnitTester(module, modulePath)

    eval(module.checkstyle(args)).fold(
      {
        case Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      numViolations => {

        val Right(report) = eval(module.checkstyleReport)

        val reported = os.exists(report.value.path)
        val countMatched = numViolations.value == expectedViolations.length

        (args.stdout && !reported && countMatched) || {

          val Right(transformedReports) = eval(module.checkstyleTransformedReports)

          val transformed = transformedReports.value.forall {
            case TransformedReport(transformation, report) =>
              transformation.path.baseName == report.path.baseName &&
              report.path.ext == (transformation.path / os.up).last
          }
          val validated = countMatched && (expectedViolations.isEmpty || {
            val lines = os.read.lines(report.value.path)
            expectedViolations.forall(expected => lines.exists(_.contains(expected)))
          })

          reported & transformed & validated
        }
      }
    )
  }

  def testJava(
      modulePath: os.Path,
      format: String = "xml",
      version: String = "10.18.1",
      options: Seq[String] = Nil,
      expectedViolations: Seq[String] = Seq.empty,
      check: Boolean = false,
      stdout: Boolean = false,
      files: Seq[String] = Seq.empty
  ): Boolean = {

    object module extends TestBaseModule with JavaModule with CheckstyleModule {
      override def checkstyleFormat = format
      override def checkstyleOptions = options
      override def checkstyleVersion = version
    }

    testCheckstyle(
      module,
      modulePath,
      expectedViolations,
      CheckstyleArgs(check, stdout, Leftover(files: _*))
    )
  }

  def testScala(
      modulePath: os.Path,
      format: String = "xml",
      version: String = "10.18.1",
      options: Seq[String] = Nil,
      expectedViolations: Seq[String] = Seq.empty,
      check: Boolean = false,
      stdout: Boolean = false,
      files: Seq[String] = Seq.empty
  ): Boolean = {

    object module extends TestBaseModule with ScalaModule with CheckstyleModule {
      override def checkstyleFormat = format
      override def checkstyleOptions = options
      override def checkstyleVersion = version
      override def scalaVersion = sys.props("MILL_SCALA_2_13_VERSION")
    }

    testCheckstyle(
      module,
      modulePath,
      expectedViolations,
      CheckstyleArgs(check, stdout, Leftover(files: _*))
    )
  }

  def tests = Tests {

    test("arguments") {

      test("check") {

        intercept[RuntimeException](
          testJava(resources / "non-compatible", check = true)
        )
      }

      test("stdout") {

        assert(
          testJava(
            resources / "non-compatible",
            expectedViolations =
              List.fill(2)("Array should contain trailing comma") :::
                List.fill(2)("Empty statement"),
            stdout = true
          )
        )
      }

      test("files") {

        assert(
          testJava(
            resources / "non-compatible",
            expectedViolations = List.fill(2)("Array should contain trailing comma"),
            files = Seq("src/blocks")
          ),
          testJava(
            resources / "non-compatible",
            expectedViolations = List.fill(4)("Array should contain trailing comma"),
            files = Seq("src/blocks", "src/blocks/ArrayTrailingComma.java")
          ),
          testJava(
            resources / "non-compatible",
            expectedViolations =
              List.fill(2)("Array should contain trailing comma") :::
                List.fill(2)("Empty statement"),
            files = Seq("src/blocks", "src/coding")
          )
        )

        intercept[RuntimeException](
          testJava(resources / "non-compatible", files = Seq("bad/path"))
        )
      }
    }

    test("settings") {

      test("format") {

        assert(
          testJava(resources / "compatible-java", "plain"),
          testJava(resources / "compatible-java", "sarif"),
          testJava(resources / "compatible-java", "xml"),
          testScala(resources / "compatible-scala", "plain"),
          testScala(resources / "compatible-scala", "sarif"),
          testScala(resources / "compatible-scala", "xml")
        )
      }

      test("options") {

        assert(
          testJava(resources / "compatible-java", options = Seq("-d"))
        )
      }

      test("version") {

        assert(
          testJava(resources / "compatible-java", "plain", "6.3"),
          testJava(resources / "compatible-java", "sarif", "8.43"),
          testJava(resources / "compatible-java", "xml", "6.3")
        )

        intercept[UnsupportedOperationException](
          testJava(resources / "compatible-java", "sarif", "8.42")
        )
      }
    }

    test("transformations") {

      assert(
        testJava(
          resources / "non-compatible",
          expectedViolations =
            List.fill(2)("Array should contain trailing comma") ::: List.fill(2)("Empty statement")
        ),
        testJava(
          resources / "sbt-checkstyle",
          expectedViolations =
            "Utility classes should not have a public or default constructor" :: Nil
        ),
        testJava(
          resources / "sbt-checkstyle-xslt",
          expectedViolations =
            "Utility classes should not have a public or default constructor" :: Nil
        )
      )
    }

    test("limitations") {

      test("report generated with unexpected violation for incompatible version") {
        assert(
          testJava(
            resources / "compatible-java",
            "plain",
            "6.2",
            expectedViolations = "File not found" :: Nil
          ),
          testJava(
            resources / "compatible-java",
            "xml",
            "6.2",
            expectedViolations = "File not found" :: Nil
          )
        )
      }

      test("cannot set options for legacy version") {
        intercept[RuntimeException](
          testJava(resources / "compatible-java", version = "6.3", options = Seq("-d"))
        )
      }
    }
  }
}
