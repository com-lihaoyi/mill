package mill
package contrib.checkstyle

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
      expectedErrors: Seq[String]
  ): Boolean = {
    val eval = UnitTester(module, modulePath)

    eval(module.checkstyle).fold(
      {
        case Result.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      checkstyle => {

        val CheckstyleOutput(errors, report, transformations) = checkstyle.value

        val reported = os.exists(report.path)
        val transformed = transformations.forall {
          case CheckstyleTransformation(definition, output) =>
            val dp = definition.path
            val op = output.path

            dp.baseName == op.baseName && op.ext == (dp / os.up).last
        }
        val validated = errors == expectedErrors.length && (expectedErrors.isEmpty || {
          val lines = os.read.lines(report.path)
          expectedErrors.forall(expected => lines.exists(_.contains(expected)))
        })

        reported & transformed & validated
      }
    )
  }

  def testJava(
      modulePath: os.Path,
      format: String = "xml",
      version: String = "10.18.1",
      options: Seq[String] = Nil,
      expectedErrors: Seq[String] = Seq.empty
  ): Boolean = {

    object module extends TestBaseModule with JavaModule with CheckstyleModule {
      override def checkstyleFormat = format
      override def checkstyleOptions = options
      override def checkstyleVersion = version
    }

    testCheckstyle(module, modulePath, expectedErrors)
  }

  def testScala(
      modulePath: os.Path,
      format: String = "xml",
      version: String = "10.18.1",
      options: Seq[String] = Nil,
      expectedErrors: Seq[String] = Seq.empty
  ): Boolean = {

    object module extends TestBaseModule with ScalaModule with CheckstyleModule {
      override def checkstyleFormat = format
      override def checkstyleOptions = options
      override def checkstyleVersion = version
      override def scalaVersion = sys.props("MILL_SCALA_2_13_VERSION")
    }

    testCheckstyle(module, modulePath, expectedErrors)
  }

  def tests = Tests {

    test("checkstyle") {

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
          resources / "noncompatible",
          expectedErrors = Seq(
            "Array should contain trailing comma",
            "Array should contain trailing comma",
            "Empty statement",
            "Empty statement"
          )
        ),
        testJava(
          resources / "sbt-checkstyle",
          expectedErrors = Seq(
            "Utility classes should not have a public or default constructor"
          )
        ),
        testJava(
          resources / "sbt-checkstyle-xslt",
          expectedErrors = Seq(
            "Utility classes should not have a public or default constructor"
          )
        )
      )
    }

    test("limitations") {

      test("report generated with cryptic error for incompatible version") {
        assert(
          testJava(
            resources / "compatible-java",
            "plain",
            "6.2",
            expectedErrors = Seq("File not found")
          ),
          testJava(
            resources / "compatible-java",
            "xml",
            "6.2",
            expectedErrors = Seq("File not found")
          )
        )
      }

      test("cannot set options for legacy version") {
        intercept[RuntimeException](
          testJava(resources / "compatible-java", "xml", "6.3", options = Seq("-d"))
        )
      }
    }
  }
}
