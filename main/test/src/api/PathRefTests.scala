package mill.api

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

import utest._

object PathRefTests extends TestSuite {
  val tests: Tests = Tests {
    "sig" - {
      def check(quick: Boolean) = withTmpDir { tmpDir =>
        val file = tmpDir / "foo.txt"
        os.write.over(file, "hello")
        val sig1 = PathRef(file, quick).sig
        val sig1b = PathRef(file, quick).sig
        assert(sig1 == sig1b)
        os.write.over(file, "hello world")
        val sig2 = PathRef(file, quick).sig
        assert(sig1 != sig2)
      }
      check(quick = true)
      check(quick = false)
    }

    "perms" - {
      def check(quick: Boolean) = withTmpDir { tmpDir =>
        val file = tmpDir / "foo.txt"
        val content = "hello"
        os.write.over(file, content)
        Files.setPosixFilePermissions(file.wrapped, PosixFilePermissions.fromString("rw-rw----"))
        val rwSig = PathRef(file, quick).sig
        val rwSigb = PathRef(file, quick).sig
        assert(rwSig == rwSigb)

        Files.setPosixFilePermissions(file.wrapped, PosixFilePermissions.fromString("rwxrw----"))
        val rwxSig = PathRef(file, quick).sig

        assert(rwSig != rwxSig)
      }
      if (isPosixFs()) {
        check(quick = true)
        check(quick = false)
      }
    }

    "symlinks" - {
      def check(quick: Boolean) = withTmpDir { tmpDir =>
        // invalid symlink
        os.symlink(tmpDir / "nolink", tmpDir / "nonexistant")

        // symlink to empty dir
        os.symlink(tmpDir / "emptylink", tmpDir / "empty")
        os.makeDir(tmpDir / "empty")

        // recursive symlinks
        os.symlink(tmpDir / "rlink1", tmpDir / "rlink2")
        os.symlink(tmpDir / "rlink2", tmpDir / "rlink1")

        val sig1 = PathRef(tmpDir, quick).sig
        val sig2 = PathRef(tmpDir, quick).sig
        assert(sig1 == sig2)
      }
      check(quick = true)
      check(quick = false)
    }
  }

  private def withTmpDir[T](body: os.Path => T): T = {
    val tmpDir = os.Path(Files.createTempDirectory(""))
    val res = body(tmpDir)
    os.remove.all(tmpDir)
    res
  }

  private def isPosixFs(): Boolean = {
    java.nio.file.FileSystems.getDefault.supportedFileAttributeViews().contains("posix")
  }
}
