package mill.scalalib.spotless

import mill.javalib.spotless.*
import mill.api.{Discover, PathRef}
import mill.scalalib.{JavaModule, ScalaModule}
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.given
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object SpotlessTests extends TestSuite {

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "spotless"

  abstract class singleModule extends TestRootModule, SpotlessModule {
    lazy val millDiscover = Discover[this.type]
  }

  object rootModule extends TestRootModule, SpotlessModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends ScalaModule {
      def scalaVersion = sys.props("TEST_SCALA_2_13_VERSION")
    }
    object foo extends JavaModule
  }

  object nestedModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    object bar extends ScalaModule, SpotlessModule {
      def scalaVersion = sys.props("TEST_SCALA_2_13_VERSION")
    }
    object foo extends JavaModule, SpotlessModule
  }

  def tests = Tests {
    test("step") {
      object module extends singleModule
      val logStream = ByteArrayOutputStream()
      val errStream = PrintStream(logStream, true)
      UnitTester(module, resources / "dirty", errStream = errStream).scoped { eval =>
        import module.moduleDir

        val numDirty = os.walk.stream(moduleDir / "src").filter(os.isFile).count()
        val Left(_) = eval("spotless", "--check"): @unchecked
        val log = logStream.toString()
        assert(log.contains(s"format errors in $numDirty files"))

        val Right(_) = eval("spotless"): @unchecked
        assert(diff(resources / "clean", moduleDir / "src") == 0)
      }
    }

    test("suppress") {
      object module extends singleModule
      val logStream = ByteArrayOutputStream()
      val errStream = PrintStream(logStream, true)
      UnitTester(module, resources / "suppress", errStream = errStream).scoped { eval =>
        import module.moduleDir

        val Left(_) = eval("spotless"): @unchecked
        var log = logStream.toString
        val lints = Seq(
          "Diktat.kt:L12 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "Diktat.kt:L13 diktat(diktat-ruleset:debug-print) [DEBUG_PRINT] use a dedicated logging library: found println()",
          "KtLint.kt:L1 ktlint(standard:no-empty-file) File 'KtLint.kt' should not be empty"
        )
        for lint <- lints do assert(log.contains(lint))

        updateFormats(moduleDir)(
          _.map(_.copy(suppressions = Seq(Format.Suppress(step = "ktlint"))))
        )
        logStream.reset()
        val Left(_) = eval("spotless"): @unchecked
        log = logStream.toString
        for lint <- lints.take(2) do assert(log.contains(lint))
        assert(
          log.contains(lints(0)),
          log.contains(lints(1)),
          !log.contains(lints(2))
        )

        updateFormats(moduleDir)(
          _.map(_.copy(suppressions = Seq(Format.Suppress()))) // suppress all
        )
        val Right(_) = eval("spotless"): @unchecked
      }
    }

    test("invalidation") {
      object module extends singleModule
      val logStream = ByteArrayOutputStream()
      val errStream = PrintStream(logStream, true)
      UnitTester(module, resources / "invalidation", errStream = errStream).scoped { eval =>
        import module.moduleDir

        val Right(_) = eval("spotless"): @unchecked
        var log = logStream.toString
        var header = os.read.lines.stream(moduleDir / "src/LicenseHeader").head
        assert(
          log.contains("formatting src/LicenseHeader"),
          header == "// GPL"
        )

        //        os.write.over(moduleDir / "LICENSE", "// MIT")
        //        logStream.reset()
        //        val Right(_) = eval("spotless")
        //        log = logStream.toString
        //        header = os.read.lines.stream(moduleDir / "src/LicenseHeader").head
        //        assert(
        //          log.contains("formatting src/LicenseHeader"),
        //          header == "// MIT"
        //        )
      }
    }

    test("ratchet") - retry(3) {
      object module extends singleModule
      val logStream = new ByteArrayOutputStream()
      val errStream = PrintStream(logStream, true)
      UnitTester(module, resources / "ratchet", errStream = errStream).scoped { eval =>
        import module.moduleDir

        def call(cmd: os.Shellable*) = os.proc(cmd*).call(cwd = moduleDir)
        def rel(path: os.Path) = path.relativeTo(moduleDir)

        val legacy = moduleDir / "src/Dirty.java"
        val legacyRef0 = PathRef(legacy)

        call("git", "init", "-b", "ratchet")
        call("git", "add", ".gitignore", legacy)
        call("git", "commit", "-m", "0") // minimum 1 commit required

        val Right(_) = eval("ratchet"): @unchecked
        var log = logStream.toString
        assert(log.contains("ratchet found no changes"))

        val patch =
          for
            i <- 0 until 5
            path = moduleDir / "src" / s"$i.java"
            _ = os.write(path, s"class Num$i  {}")
          yield path

        call("git", "add", "--all")
        logStream.reset()
        val Right(_) = eval("ratchet", "--staged"): @unchecked
        log = logStream.toString
        assert(
          log.contains(s"ratchet found changes in ${patch.length} files"),
          log.contains(s"formatting ${rel(patch(0))}"),
          log.contains(s"formatting ${rel(patch(1))}"),
          log.contains(s"formatting ${rel(patch(2))}"),
          log.contains(s"formatting ${rel(patch(3))}"),
          log.contains(s"formatting ${rel(patch(4))}"),
          log.contains(s"formatted ${patch.length} files")
        )

        call("git", "add", "--all") // re-stage formatted files
        call("git", "commit", "-m", "1")
        logStream.reset()
        val Right(_) = eval("ratchet", "HEAD^", "HEAD"): @unchecked
        log = logStream.toString
        assert(
          log.contains(s"ratchet found changes in ${patch.length} files"),
          log.contains(s"${patch.length} java files are already formatted")
        )

        val pkg = moduleDir / "src/ab"
        os.makeDir.all(pkg)
        os.write.over(patch(0), "class  Zero { }") // ChangeType.MODIFY
        os.remove(patch(1)) // ChangeType.DELETE
        os.move(patch(2), pkg / patch(2).last) // ChangeType.RENAME
        os.copy(patch(3), pkg / patch(3).last) // ChangeType.COPY
        logStream.reset()
        val Right(_) = eval("ratchet"): @unchecked
        log = logStream.toString
        assert(
          log.contains("ratchet found changes in 3 files"), // DELETE is ignored
          log.contains("checking format in 3 java files"), // 1 each for MODIFY/RENAME/COPY
          log.contains(s"formatting ${rel(patch(0))}"), // for MODIFY
          log.contains("formatted 1 files") // content is clean for RENAME/COPY
        )

        assert(legacyRef0.validate())
      }
    }

    test("rootModule") {
      val logStream = ByteArrayOutputStream()
      val errStream = PrintStream(logStream, true)
      UnitTester(rootModule, resources / "multiModule", errStream = errStream).scoped { eval =>
        val Left(_) = eval("spotless", "--check"): @unchecked
        var log = logStream.toString
        assert(
          log.contains("format errors in bar/src/bar/Bar.scala"),
          log.contains("format errors in foo/src/foo/Foo.java"),
          log.contains("format errors in 2 files")
        )

        logStream.reset()
        val Right(_) = eval("spotless"): @unchecked
        log = logStream.toString
        assert(
          log.contains("formatting bar/src/bar/Bar.scala"),
          log.contains("formatting foo/src/foo/Foo.java"),
          log.contains("formatted 2 files")
        )
      }
    }

    test("nestedModule") {
      val logStream = ByteArrayOutputStream()
      val errStream = PrintStream(logStream, true)
      UnitTester(nestedModule, resources / "multiModule", errStream = errStream).scoped { eval =>
        val Left(_) = eval("bar.spotless", "--check"): @unchecked
        var log = logStream.toString
        assert(
          log.contains("format errors in src/bar/Bar.scala"),
          log.contains("format errors in 1 files")
        )

        logStream.reset()
        val Left(_) = eval("foo.spotless", "--check"): @unchecked
        log = logStream.toString
        assert(
          log.contains("format errors in src/foo/Foo.java"),
          log.contains("format errors in 1 files")
        )

        logStream.reset()
        val Right(_) = eval("__.spotless"): @unchecked
        log = logStream.toString
        assert(
          log.contains("formatting src/bar/Bar.scala"),
          log.contains("formatted 1 files"),
          log.contains("formatting src/foo/Foo.java"),
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

private def updateFormats(cwd: os.Path)(f: Seq[Format] => Seq[Format]) =
  val file = cwd / ".spotless-formats.json"
  os.write.over(
    file,
    upickle.default.write(f(upickle.default.read[Seq[Format]](file.toNIO)))
  )
