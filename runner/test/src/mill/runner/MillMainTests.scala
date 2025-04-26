package mill.runner

import utest._
import mill.api.Result
import scala.util.chaining._

object MillMainTests extends TestSuite {

  private def assertParseErr(result: Result[Int], msg: String): Unit = {
    assert(result.isInstanceOf[Result.Failure])
    assert(result.toEither.swap.toOption.get.contains(msg))
  }

  def tests: Tests = Tests {

    test("Parsing --jobs/-j flag") {

      test("parse none") {
        assert(MillMain.parseThreadCount(None, 10) == Result.Success(10))
      }

      test("parse int number") {
        assert(MillMain.parseThreadCount(Some("1"), 10) == Result.Success(1))
        assert(MillMain.parseThreadCount(Some("11"), 10) == Result.Success(11))

        assertParseErr(MillMain.parseThreadCount(Some("1.0"), 10), "Failed to find a int number")
        assertParseErr(MillMain.parseThreadCount(Some("1.1"), 10), "Failed to find a int number")
        assertParseErr(MillMain.parseThreadCount(Some("0.1"), 10), "Failed to find a int number")
        assert(MillMain.parseThreadCount(Some("0"), 10) == Result.Success(10))
        assert(MillMain.parseThreadCount(Some("-1"), 10) == Result.Success(1))
      }

      test("parse fraction number") {
        assert(MillMain.parseThreadCount(Some("0.5C"), 10) == Result.Success(5))
        assert(MillMain.parseThreadCount(Some("0.54C"), 10) == Result.Success(5))
        assert(MillMain.parseThreadCount(Some("0.59C"), 10) == Result.Success(5))
        assert(MillMain.parseThreadCount(Some(".5C"), 10) == Result.Success(5))
        assert(MillMain.parseThreadCount(Some("1.0C"), 10) == Result.Success(10))
        assert(MillMain.parseThreadCount(Some("1.5C"), 10) == Result.Success(15))
        assert(MillMain.parseThreadCount(Some("0.09C"), 10) == Result.Success(1))
        assert(MillMain.parseThreadCount(Some("-0.5C"), 10) == Result.Success(1))
        assertParseErr(
          MillMain.parseThreadCount(Some("0.5.4C"), 10),
          "Failed to find a float number before \"C\""
        )
      }

      test("parse subtraction") {
        assert(MillMain.parseThreadCount(Some("C-1"), 10) == Result.Success(9))
        assert(MillMain.parseThreadCount(Some("C-10"), 10) == Result.Success(1))
        assert(MillMain.parseThreadCount(Some("C-11"), 10) == Result.Success(1))

        assertParseErr(
          MillMain.parseThreadCount(Some("C-1.1"), 10),
          "Failed to find a int number after \"C-\""
        )
        assertParseErr(
          MillMain.parseThreadCount(Some("11-C"), 10),
          "Failed to find a float number before \"C\""
        )
      }

      test("parse invalid input") {
        assertParseErr(
          MillMain.parseThreadCount(Some("CCCC"), 10),
          "Failed to find a float number before \"C\""
        )
        assertParseErr(
          MillMain.parseThreadCount(Some("abcdefg"), 10),
          "Failed to find a int number"
        )
      }

    }

    test("read mill version") {
      test("from .mill-version") {
        val file = os.temp.dir() / ".mill-version"
        os.write(file, "1.2.3")
        val read = MillMain.readVersionFile(file)
        assert(read == Some("1.2.3"))
      }
      test("from .config/mill-version") {
        val file = os.temp.dir() / ".config" / "mill-version"
        os.write(file, "1.2.3", createFolders = true)
        val read = MillMain.readVersionFile(file)
        assert(read == Some("1.2.3"))
      }
      test("from build.mill") {
        val file = os.temp.dir() / "build.mill"
        os.write(file, "//| mill-version: 1.2.3")
        val read = MillMain.readUsingMillVersionFile(file)
        assert(read == Some("1.2.3"))
      }
      test("precedence") {
        val dir = os.temp.dir()
        val file1 = (dir / ".mill-version").tap { os.write(_, "1") }
        val file2 =
          (dir / ".config" / "mill-version").tap { os.write(_, "2", createFolders = true) }
        val file3 = (dir / "build.mill").tap { os.write(_, "//| mill-version: 3") }
        // Added content spaces to test parsing
        val file4 = (dir / "build.mill.scala").tap { os.write(_, "//| mill-version:    4") }
        val file5 = (dir / "build.sc").tap { os.write(_, "//|     mill-version: 5") }
        test(".mill-version") {
          val read = MillMain.readBestMillVersion(dir)
          assert(read == Some(file1, "1"))
        }
        test(".config/mill-version") {
          os.remove(file1)
          val read = MillMain.readBestMillVersion(dir)
          assert(read == Some(file2, "2"))
        }
        test("build.mill") {
          os.remove(file1)
          os.remove(file2)
          val read = MillMain.readBestMillVersion(dir)
          assert(read == Some(file3, "3"))
        }
        test("build.mill.scala") {
          os.remove(file1)
          os.remove(file2)
          os.remove(file3)
          val read = MillMain.readBestMillVersion(dir)
          assert(read == Some(file4, "4"))
        }
        test("build.sc") {
          os.remove(file1)
          os.remove(file2)
          os.remove(file3)
          os.remove(file4)
          val read = MillMain.readBestMillVersion(dir)
          assert(read == Some(file5, "5"))
        }
      }
    }

  }
}
