package mill.javalib.pmd

import mill.*
import mainargs.Leftover
import mill.define.Discover
import mill.scalalib.{JavaModule, ScalaModule}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object PmdModuleTest extends TestSuite {

  def tests: Tests = Tests {
    val resources: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "pmd"

    test("Pmd matches violation comments in example sources") {
      val modulePath = resources / "example"
      object module extends TestRootModule with JavaModule with PmdModule {
        lazy val millDiscover = Discover[this.type]
      }
      UnitTester(module, modulePath).scoped { eval =>
        // Run with check = false so it does not throw on violations
        eval(module.pmd(PmdArgs(check = false, stdout = false, sources = Leftover("sources"))))

        val outputReportEither = eval(module.pmdOutput)
        val outputReportPath = outputReportEither match {
          case Right(result) => result.value.path
          case Left(failing) =>
            throw new Exception(s"PMD output generation failed: $failing")
        }

        // Find all expected violations from source files
        val javaFiles = os.walk(modulePath).filter(_.ext == "java")
        val expectedViolations = javaFiles.flatMap { file =>
          os.read.lines(file).zipWithIndex.collect {
            case (line, idx) if line.contains("// violation") =>
              // Normalize all path separators to '/' for cross-platform compatibility
              (file.relativeTo(modulePath).toString.replace("\\", "/"), idx + 1)
          }
        }.toSet

        // Parse PMD report for actual violations (text format assumed)
        val reportLines = os.read.lines(outputReportPath)
        val violationRegex = """(.+):(\d+):\s+.*""".r
        val reportedViolations = reportLines.collect {
          case violationRegex(file, line) =>
            // Normalize all path separators to '/' for cross-platform compatibility
            (file.trim.replace("\\", "/"), line.toInt)
        }.toSet

        // PMD sometimes reports paths relative to the cwd or absolute; try to normalize
        def normalize(path: String): String =
          path.replace("\\", "/")

        val expectedNormalized = expectedViolations.map { case (file, line) =>
          (normalize(file), line)
        }
        val reportedNormalized = reportedViolations.map { case (file, line) =>
          (normalize(file), line)
        }

        assert(expectedNormalized == reportedNormalized)
      }
    }
  }
}
