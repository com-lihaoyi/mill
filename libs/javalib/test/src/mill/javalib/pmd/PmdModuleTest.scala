package mill.javalib.pmd

import mill.*
import mainargs.Leftover
import mill.api.Discover
import mill.javalib.JavaModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object PmdModuleTest extends TestSuite {

  val resources: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "pmd"
  val modulePath: os.Path = resources / "example"

  def runTest(module: TestRootModule & PmdModule & JavaModule): Unit = {
    UnitTester(module, modulePath).scoped { eval =>
      val format = "text"
      eval(module.pmd(PmdArgs(
        failOnViolation = false,
        format = format,
        sources = Leftover("sources")
      )))

      val outputReportPath = eval.outPath / "pmd.dest" / s"pmd-output.$format"

      val javaFiles = os.walk(modulePath).filter(_.ext == "java")
      val expectedViolations = javaFiles.flatMap { file =>
        os.read.lines(file).zipWithIndex.collect {
          case (line, idx) if line.contains("// violation") =>
            (file.relativeTo(modulePath).toString.replace("\\", "/"), idx + 1)
        }
      }.toSet

      val reportLines = os.read.lines(outputReportPath)
      val violationRegex = """(.+):(\d+):\s+.*""".r
      val reportedViolations = reportLines.collect {
        case violationRegex(file, line) =>
          (file.trim.replace("\\", "/"), line.toInt)
      }.toSet

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

  def tests: Tests = Tests {
    // Default test
    test("Pmd matches violation comments in example sources (default)") {
      val module = new TestRootModule with PmdModule with JavaModule {
        lazy val millDiscover = Discover[this.type]
      }
      runTest(module)
    }

    // Older PMD version test
    test("Pmd matches violation comments in example sources (pmd6)") {
      val module = new TestRootModule with PmdModule with JavaModule {
        override def pmdVersion = Task { "6.55.0" }
        lazy val millDiscover = Discover[this.type]
      }
      runTest(module)
    }

    // PMD 7.x test
    test("Pmd matches violation comments in example sources (pmd7)") {
      val module = new TestRootModule with PmdModule with JavaModule {
        override def pmdVersion = Task { "7.15.0" }
        lazy val millDiscover = Discover[this.type]
      }
      runTest(module)
    }
  }
}
