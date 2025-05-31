package mill.scalalib.spotless

import mill.define.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object SpotlessTests extends TestSuite {

  object spotlessModule extends TestRootModule, SpotlessModule {
    lazy val millDiscover = Discover[this.type]
    def spotlessTargets = Task.Sources("src")
  }

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "spotless"

  def tests = Tests {
    test("step") {
      UnitTester(spotlessModule, resources / "dirty").scoped { eval =>
        val result = eval("spotless")
        assert(
          result.isRight,
          diff(resources / "clean", spotlessModule.moduleDir / "src") == 0
        )
      }
    }
    test("suppress") {
      val logStream = ByteArrayOutputStream()
      UnitTester(
        spotlessModule,
        resources / "suppress",
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val result0 = eval("spotless")
        assert(result0.isLeft)
        val log = logStream.toString
        val lints = Seq(
          "Diktat.kt:L12 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "Diktat.kt:L13 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "KtLint.kt:L1 ktlint(standard:no-empty-file) File 'KtLint.kt' should not be empty"
        )
        for lint <- lints do assert(log.contains(lint))

        val formatsFile = spotlessModule.moduleDir / "spotless-formats.json"
        val formats =
          upickle.default.read[Seq[Format]](os.read(formatsFile)).map(_.copy(suppressions =
            Seq(
              Format.Suppress(step = "diktat"),
              Format.Suppress(step = "ktlint", shortCode = "standard:no-empty-file")
            )
          ))
        os.write.over(formatsFile, upickle.default.write(formats))
        val result1 = eval("spotless")
        assert(result1.isRight)
      }
    }
    test("invalidation") {
      val logStream = ByteArrayOutputStream()
      UnitTester(
        spotlessModule,
        resources / "invalidation",
        outStream = PrintStream(logStream, true),
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val moduleDir = spotlessModule.moduleDir

        val result0 = eval("spotless")
        var log = logStream.toString
        val header0 = os.read.lines.stream(moduleDir / "src/LicenseHeader").head
        assert(
          result0.isRight,
          log.contains("checking format in 1 LicenseHeader files"),
          header0 == "// GPL"
        )

        logStream.reset()
        val result1 = eval("spotless")
        log = logStream.toString
        assert(
          result1.isRight,
          log.contains("everything is already formatted"),
          !log.contains("checking format in 1 LicenseHeader files")
        )

        os.write.over(moduleDir / "LICENSE", "// MIT")
        logStream.reset()
        val result2 = eval("spotless")
        log = logStream.toString
        val header2 = os.read.lines.stream(moduleDir / "src/LicenseHeader").head
        assert(
          result2.isRight,
          log.contains("checking format in 1 LicenseHeader files"),
          !log.contains("everything is already formatted"),
          header2 == "// MIT"
        )
      }
    }
  }
}

private def diff(src: os.Path, dst: os.Path) =
  os.proc("git", "diff", "--no-index", src, dst)
    .call(check = false, stdout = os.Inherit)
    .exitCode
