package mill.scalalib.spotless

import mill.define.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object SpotlessTests extends TestSuite {

  object testModule extends TestRootModule, SpotlessModule {
    lazy val millDiscover = Discover[this.type]
  }

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "spotless"

  def tests = Tests {
    test("check") {
      val logStream = ByteArrayOutputStream()
      UnitTester(
        testModule,
        resources / "dirty",
        outStream = PrintStream(logStream, true),
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("spotless", "--check")
        val log = logStream.toString()
        assert(log.contains("format errors in 15 files"))
      }
    }

    test("step") {
      UnitTester(testModule, resources / "dirty").scoped { eval =>
        val Right(_) = eval("spotless")
        assert(
          diff(resources / "clean", testModule.moduleDir / "src") == 0
        )
      }
    }

    test("suppress") {
      val logStream = ByteArrayOutputStream()
      UnitTester(
        testModule,
        resources / "suppress",
        outStream = PrintStream(logStream, true),
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Left(_) = eval("spotless")
        val log = logStream.toString
        val lints = Seq(
          "Diktat.kt:L12 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "Diktat.kt:L13 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "KtLint.kt:L1 ktlint(standard:no-empty-file) File 'KtLint.kt' should not be empty"
        )
        for lint <- lints do assert(log.contains(lint))

        val formatsFile = testModule.moduleDir / "spotless-formats.json"
        val formats = upickle.default.read[Seq[Format]](os.read(formatsFile))
        os.write.over(
          formatsFile,
          upickle.default.write(formats.map(_.copy(suppressions =
            Seq(
              Format.Suppress(step = "diktat"),
              Format.Suppress(step = "ktlint", shortCode = "standard:no-empty-file")
            )
          )))
        )
        val Right(_) = eval("spotless")
      }
    }

    test("invalidation") {
      val logStream = ByteArrayOutputStream()
      UnitTester(
        testModule,
        resources / "invalidation",
        outStream = PrintStream(logStream, true),
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val moduleDir = testModule.moduleDir

        val Right(_) = eval("spotless")
        var log = logStream.toString
        var header = os.read.lines.stream(moduleDir / "src/LicenseHeader").head
        assert(
          log.contains("formatting src/LicenseHeader"),
          header == "// GPL"
        )

        os.write.over(moduleDir / "LICENSE", "// MIT")
        logStream.reset()
        val Right(_) = eval("spotless")
        log = logStream.toString
        header = os.read.lines.stream(moduleDir / "src/LicenseHeader").head
        assert(
          log.contains("formatting src/LicenseHeader"),
          header == "// MIT"
        )
      }
    }

    test("matchers") {
      val logStream = ByteArrayOutputStream()
      UnitTester(
        testModule,
        resources / "matchers",
        outStream = PrintStream(logStream, true),
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        val Right(_) = eval("spotless")
        val log = logStream.toString()
        assert(
          log.contains("formatting src/A"),
          log.contains("formatted 1 files")
        )
      }
    }
  }
}

private def diff(src: os.Path, dst: os.Path) =
  os.proc("git", "diff", "--no-index", src, dst)
    .call(check = false, stdout = os.Inherit)
    .exitCode
