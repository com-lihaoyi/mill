package mill.scalalib.spotless

import mainargs.Flag
import mill.define.{Discover, PathRef, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.time.YearMonth

object GitTests extends TestSuite {

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "spotless" / "git"

  def tests = Tests {

    test("LicenseHeader") {
      object testModule extends TestRootModule, SpotlessModule {
        lazy val millDiscover = Discover[this.type]
      }

      UnitTester(testModule, resources / "license").scoped { eval =>
        def call(cmd: os.Shellable*) =
          os.proc(cmd*).call(cwd = testModule.moduleDir, stderr = os.Pipe)

        val target = testModule.moduleDir / "src/A"
        val source0 = os.read(target)
        val currentYear = YearMonth.now().getYear.toString

        call("git", "init", "-b", "license")
        call("git", "add", target)
        call("git", "commit", "-m", "prepped")

        // test no header
        val Right(_) = eval("spotless")
        var header = os.read.lines(target).head
        assert(header == s"// GPL $currentYear")

        // test outdated single year
        os.write.over(
          target,
          s"""// GPL 2023
             |$source0""".stripMargin
        )
        val Right(_) = eval("spotless")
        header = os.read.lines(target).head
        assert(header == s"// GPL $currentYear")

        // testing outdated year range requires at least two commits that are a year apart
        // otherwise the range is replaced by a single year
        os.write.over(
          target,
          s"""// GPL 2023-2024
             |$source0""".stripMargin
        )
        val Right(_) = eval("spotless")
        header = os.read.lines(target).head
        assert(header == s"// GPL $currentYear")
      }
    }

    test("ratchet") {
      object testModule extends TestRootModule {
        lazy val millDiscover = Discover[this.type]

        def ratchet(
            @mainargs.arg(positional = true)
            oldRev: String = "HEAD",
            @mainargs.arg(positional = true)
            newRev: Option[String] = None,
            check: Flag = Flag()
        ) = Task.Command {
          SpotlessModule.ratchet(oldRev, newRev, check)()
        }
      }

      val logStream = new ByteArrayOutputStream()
      UnitTester(
        testModule,
        resources / "ratchet",
        errStream = PrintStream(logStream, true)
      ).scoped { eval =>
        def call(cmd: os.Shellable*) =
          os.proc(cmd*).call(cwd = testModule.moduleDir, stderr = os.Pipe)
        def rel(path: os.Path) =
          path.relativeTo(testModule.moduleDir)

        val target = testModule.moduleDir / "src/Dirty.java"
        val targetRef0 = PathRef(target)

        call("git", "init", "-b", "ratchet")
        call("git", "add", ".gitignore", target)
        call("git", "commit", "-m", "prepped")

        val Right(_) = eval("ratchet")
        var log = logStream.toString
        assert(log.contains("ratchet found no changes"))

        val patch =
          for
            i <- 0 until 5
            path = testModule.moduleDir / "src" / s"$i.java"
            _ = os.write(path, s"class Num$i  {}")
          yield path

        logStream.reset()
        val Right(_) = eval("ratchet")
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

        call("git", "add", "--all")
        logStream.reset()
        val Right(_) = eval("ratchet")
        log = logStream.toString
        assert(
          log.contains(s"ratchet found changes in ${patch.length} files"),
          log.contains("everything is already formatted")
        )

        call("git", "commit", "-m", "1")
        logStream.reset()
        val Right(_) = eval("ratchet")
        log = logStream.toString
        assert(log.contains("ratchet found no changes"))

        logStream.reset()
        val Right(_) = eval("ratchet", "HEAD^", "HEAD")
        log = logStream.toString
        assert(
          log.contains(s"ratchet found changes in ${patch.length} files"),
          log.contains("everything is already formatted")
        )

        val pkg = testModule.moduleDir / "src/ab"
        os.makeDir.all(testModule.moduleDir / "src/ab")
        os.write.over(patch(0), "class  Zero { }") // ChangeType.MODIFY
        os.remove(patch(1)) // ChangeType.DELETE
        os.move(patch(2), pkg / patch(2).last) // ChangeType.RENAME
        os.copy(patch(3), pkg / patch(3).last) // ChangeType.COPY
        logStream.reset()
        val Right(_) = eval(testModule.ratchet())
        log = logStream.toString
        assert(
          log.contains("ratchet found changes in 3 files"), // DELETE is ignored
          log.contains("checking format in 3 java files"), // 1 each for MODIFY/RENAME/COPY
          log.contains(s"formatting ${rel(patch(0))}"), // for MODIFY
          log.contains("formatted 1 files") // content is clean for RENAME/COPY
        )

        assert(targetRef0.validate())
      }
    }
  }
}
