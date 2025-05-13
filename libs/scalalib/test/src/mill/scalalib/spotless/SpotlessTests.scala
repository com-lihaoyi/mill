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
        val log0 = logStream.toString
        val lints = Seq(
          "Diktat.kt:L12 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "Diktat.kt:L13 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "KtLint.kt:L1 ktlint(standard:no-empty-file) File 'KtLint.kt' should not be empty"
        )
        for lint <- lints do assert(log0.contains(lint))

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
        val log0 = logStream.toString
        val header0 = os.read.lines.stream(moduleDir / "src/LicenseHeader").take(2).toSeq
        assert(
          result0.isRight,
          log0.contains("checking format in 1 LicenseHeader files"),
          header0.head == "// If you can't trust a man's word",
          header0.last == "// Does it help to have it in writing?"
        )

        logStream.reset()
        val result1 = eval("spotless")
        val log1 = logStream.toString
        assert(
          result1.isRight,
          !log1.contains("checking format in 1 LicenseHeader files"),
          log1.contains("everything is already formatted")
        )

        logStream.reset()
        os.write.over(moduleDir / "LICENSE", "// MIT")
        val result2 = eval("spotless")
        val log2 = logStream.toString
        val header2 = os.read.lines.stream(moduleDir / "src/LicenseHeader").take(2).toSeq
        assert(
          result2.isRight,
          log2.contains("checking format in 1 LicenseHeader files"),
          !log2.contains("everything is already formatted"),
          header2.head == "// MIT",
          header2.last == "package com.github.youribonnaffe.gradle.format;"
        )
      }
    }
  }
}

def diff(src: os.Path, dst: os.Path) =
  os.proc("git", "diff", "--no-index", src, dst)
    .call(check = false, stdout = os.Inherit)
    .exitCode
