package mill.javalib.checkstyle

import mill._
import mainargs.Leftover
import mill.scalalib.{JavaModule, ScalaModule}
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object CheckstyleModuleTest extends TestSuite {

  def tests: Tests = Tests {

    val resources: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "checkstyle"

    // violations (for version 10.18.1) in "non-compatible" module
    val violations: Seq[String] =
      Seq.fill(2)("Array should contain trailing comma") ++
        Seq.fill(2)("Empty statement")

    test("arguments") {

      test("check") {

        intercept[RuntimeException](
          testJava(resources / "non-compatible", check = true)
        )
      }

      test("stdout") {

        assert(
          testJava(resources / "non-compatible", violations = violations, stdout = true)
        )
      }

      test("sources") {

        assert(
          testJava(
            resources / "non-compatible",
            violations = violations.take(2),
            sources = Seq("src/blocks")
          ),
          testJava(
            resources / "non-compatible",
            violations = violations.take(2) ++ violations.take(2),
            sources = Seq("src/blocks", "src/blocks/ArrayTrailingComma.java")
          ),
          testJava(
            resources / "non-compatible",
            violations = violations,
            sources = Seq("src/blocks", "src/coding")
          )
        )

        intercept[UnsupportedOperationException](
          testJava(resources / "non-compatible", sources = Seq("hope/this/path/does/not/exist"))
        )
      }
    }

    test("settings") {

      test("format") {

        assert(
          testJava(resources / "non-compatible", "plain", violations = violations),
          testJava(resources / "non-compatible", "sarif", violations = violations),
          testJava(resources / "non-compatible", "xml", violations = violations),
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

    test("limitations") {

      test("incompatible version generates report with unexpected violation") {
        assert(
          testJava(
            resources / "compatible-java",
            "plain",
            "6.2",
            violations = Seq("File not found")
          ),
          testJava(
            resources / "compatible-java",
            "xml",
            "6.2",
            violations = Seq("File not found")
          )
        )
      }

      test("cannot set options for legacy version") {
        intercept[UnsupportedOperationException](
          testJava(resources / "compatible-java", version = "6.3", options = Seq("-d"))
        )
      }
    }
  }

  def testJava(
      modulePath: os.Path,
      format: String = "xml",
      version: String = "10.18.1",
      options: Seq[String] = Nil,
      violations: Seq[String] = Seq.empty,
      check: Boolean = false,
      stdout: Boolean = false,
      sources: Seq[String] = Seq.empty
  ): Boolean = {

    object module extends TestBaseModule with JavaModule with CheckstyleModule {
      override def checkstyleFormat: T[String] = format
      override def checkstyleOptions: T[Seq[String]] = options
      override def checkstyleVersion: T[String] = version
    }

    testModule(
      module,
      modulePath,
      violations,
      CheckstyleArgs(check, stdout, Leftover(sources: _*))
    )
  }

  def testScala(
      modulePath: os.Path,
      format: String = "xml",
      version: String = "10.18.1",
      options: Seq[String] = Nil,
      violations: Seq[String] = Seq.empty,
      check: Boolean = false,
      stdout: Boolean = false,
      sources: Seq[String] = Seq.empty
  ): Boolean = {

    object module extends TestBaseModule with ScalaModule with CheckstyleModule {
      override def checkstyleFormat: T[String] = format
      override def checkstyleOptions: T[Seq[String]] = options
      override def checkstyleVersion: T[String] = version
      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
    }

    testModule(
      module,
      modulePath,
      violations,
      CheckstyleArgs(check, stdout, Leftover(sources: _*))
    )
  }

  def testModule(
      module: TestBaseModule with CheckstyleModule,
      modulePath: os.Path,
      violations: Seq[String],
      args: CheckstyleArgs
  ): Boolean = {
    val eval = UnitTester(module, modulePath)
    eval(module.checkstyle(args)).fold(
      {
        case api.Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      numViolations => {

        numViolations.value == violations.length && {

          val Right(report) = eval(module.checkstyleOutput)

          if (os.exists(report.value.path)) {
            violations.isEmpty || {
              val lines = os.read.lines(report.value.path)
              violations.forall(violation => lines.exists(_.contains(violation)))
            }
          } else
            args.stdout
        }
      }
    )
  }
}
