package mill
package contrib.checkstyle

import mill.api.Result
import mill.scalalib.{JavaModule, ScalaModule}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object CheckstyleModuleTest extends TestSuite {

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))

  def tests = Tests {

    test("checkstyle") {

      test("format") {

        test("plain") {
          assert(
            cs("plain").checkJava(resources / "compatible-java"),
            cs("plain").checkJava(
              resources / "sbt-checkstyle",
              "Utility classes should not have a public or default constructor"
            ),
            cs("plain").checkScala(resources / "compatible-scala")
          )
        }

        test("sarif") {
          assert(
            cs("sarif").checkJava(resources / "compatible-java"),
            cs("sarif").checkJava(
              resources / "sbt-checkstyle",
              "Utility classes should not have a public or default constructor"
            ),
            cs("sarif").checkScala(resources / "compatible-scala")
          )
        }

        test("xml") {
          assert(
            cs("xml").checkJava(resources / "compatible-java"),
            cs("xml").checkJava(
              resources / "sbt-checkstyle",
              "Utility classes should not have a public or default constructor"
            ),
            cs("xml").checkJava(
              resources / "sbt-checkstyle-xslt",
              "Utility classes should not have a public or default constructor"
            ),
            cs("xml").checkScala(resources / "compatible-scala")
          )
        }
      }

      test("options") {

        assert(
          // Checkstyle exits with org.apache.commons.cli.UnrecognizedOptionException for any option
          // cs("xml", "6.3", options = Seq("-d")).checkJava(resources / "compatible-java"),
          cs("xml", options = Seq("-d")).checkJava(resources / "compatible-java")
        )
      }

      test("version") {

        test("plain") {
          assert(
            // instead of exiting Checkstyle generates a report with a cryptic error
            cs("plain", "6.2").checkJava(resources / "compatible-java", "File not found"),
            cs("plain", "6.3").checkJava(resources / "compatible-java")
          )
        }

        test("sarif") {
          intercept[UnsupportedOperationException](
            cs("sarif", "8.42").checkJava(resources / "compatible-java")
          )
          assert(
            cs("sarif", "8.43").checkJava(resources / "compatible-java")
          )
        }

        test("xml") {
          assert(
            // instead of exiting Checkstyle generates a report with a cryptic error
            cs("xml", "6.2").checkJava(resources / "compatible-java", "File not found"),
            cs("xml", "6.3").checkJava(resources / "compatible-java")
          )
        }
      }
    }
  }

  case class cs(format: String, version: String = "10.18.1", options: Seq[String] = Nil) {

    def check(
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

          val Checkstyle(errors, report, transformations) = checkstyle.value

          val Right(transforms) = eval(module.checkstyleTransforms)

          val reported = os.exists(report.path)
          val transformed = transforms.value.forall {
            case (_, relPath) => transformations.exists(_.path.endsWith(os.rel / relPath))
          }
          val validated = errors == expectedErrors.length && (expectedErrors.isEmpty || {
            val lines = os.read.lines(report.path)
            expectedErrors.forall(expected => lines.exists(_.contains(expected)))
          })

          reported & transformed & validated
        }
      )
    }

    def checkJava(modulePath: os.Path, expectedErrors: String*): Boolean = {

      object module extends TestBaseModule with JavaModule with CheckstyleModule {
        override def checkstyleFormat = format
        override def checkstyleOptions = options
        override def checkstyleVersion = version
      }

      check(module, modulePath, expectedErrors)
    }

    def checkScala(modulePath: os.Path, expectedErrors: String*): Boolean = {

      object module extends TestBaseModule with ScalaModule with CheckstyleModule {
        override def checkstyleFormat = format
        override def checkstyleOptions = options
        override def checkstyleVersion = version
        override def scalaVersion = sys.props("MILL_SCALA_2_13_VERSION")
      }

      check(module, modulePath, expectedErrors)
    }
  }
}
