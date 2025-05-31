package mill.scalalib.spotless

import mainargs.Flag
import mill.define.{Discover, PathRef, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object RatchetTests extends TestSuite {

  object testModule extends TestRootModule {
    lazy val millDiscover = Discover[this.type]
    def ratchet(
        oldRev: String = "HEAD",
        newRev: String = null,
        check: Boolean = false
    ) = Task.Command {
      SpotlessModule.ratchet(oldRev, Option(newRev), Flag(check))()
    }
  }

  val ratchetResources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "spotless" / "ratchet"

  def tests = Tests {
    val logStream = new ByteArrayOutputStream()
    UnitTester(
      testModule,
      ratchetResources,
      outStream = PrintStream(logStream, true),
      errStream = PrintStream(logStream, true)
    ).scoped { eval =>
      def call(cmd: os.Shellable*) =
        os.proc(cmd*).call(cwd = testModule.moduleDir, stderr = os.Pipe)

      val dirty = testModule.moduleDir / "src/Dirty.java"
      val dirtyRef0 = PathRef(dirty)

      call("git", "init", "-b", "ratchet")
      call("git", "add", ".gitignore", dirty)
      call("git", "commit", "-m", "prep")

      val Right(_) = eval(testModule.ratchet())
      var log = logStream.toString
      assert(
        log.contains("ratchet found no changes")
      )

      val patch =
        for
          i <- 0 until 5
          path = testModule.moduleDir / "src" / s"$i.java"
          _ = os.write(path, s"class Num$i  {}")
        yield path

      logStream.reset()
      val Right(_) = eval(testModule.ratchet())
      log = logStream.toString
      assert(
        log.contains(s"ratchet found changes in ${patch.length} files"),
        log.contains(s"formatting ${patch(0).subRelativeTo(testModule.moduleDir)}"),
        log.contains(s"formatting ${patch(1).subRelativeTo(testModule.moduleDir)}"),
        log.contains(s"formatting ${patch(2).subRelativeTo(testModule.moduleDir)}"),
        log.contains(s"formatting ${patch(3).subRelativeTo(testModule.moduleDir)}"),
        log.contains(s"formatting ${patch(4).subRelativeTo(testModule.moduleDir)}"),
        log.contains(s"formatted ${patch.length} files")
      )

      call("git", "add", "--all")
      logStream.reset()
      val Right(_) = eval(testModule.ratchet())
      log = logStream.toString
      assert(
        log.contains(s"ratchet found changes in ${patch.length} files"),
        log.contains("everything is already formatted")
      )

      call("git", "commit", "-m", "1")
      logStream.reset()
      val Right(_) = eval(testModule.ratchet())
      log = logStream.toString
      assert(
        log.contains("ratchet found no changes")
      )

      logStream.reset()
      val Right(_) = eval(testModule.ratchet("HEAD^", "HEAD"))
      log = logStream.toString
      assert(
        log.contains(s"ratchet found changes in ${patch.length} files"),
        log.contains("everything is already formatted")
      )

      os.write.over(patch(0), "class  Zero { }")
      os.remove(patch(1))
      os.move(patch(2), patch(2) / os.up / "num" / patch(2).last, createFolders = true)
      os.copy(patch(3), patch(3) / os.up / "num" / patch(3).last)
      logStream.reset()
      val Right(_) = eval(testModule.ratchet())
      log = logStream.toString
      assert(
        log.contains("ratchet found changes in 3 files"), // ignore delete
        log.contains("checking format in 3 java files"), // cache miss
        log.contains(s"formatting ${patch(0).subRelativeTo(testModule.moduleDir)}"),
        log.contains("formatted 1 files") // content already formatted for move/copy
      )

      assert(dirtyRef0.validate())
    }
  }
}
