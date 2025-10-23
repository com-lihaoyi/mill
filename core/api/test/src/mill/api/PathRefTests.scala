package mill.api

import utest.*

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Properties

object PathRefTests extends TestSuite {
  val tests: Tests = Tests {
    test("sig") {
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
      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("same-sig-other-file") {
      def check(quick: Boolean) = withTmpDir { tmpDir =>
        val file = tmpDir / "foo.txt"
        os.write.over(file, "hello")
        val sig1 = PathRef(file, quick).sig
        val file2 = tmpDir / "bar.txt"
        os.copy(file, file2)
        val sig1b = PathRef(file2, quick).sig
        assert(sig1 == sig1b)
      }
//      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("perms") {
      def check(quick: Boolean) =
        if (isPosixFs()) withTmpDir { tmpDir =>
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
        else "Test Skipped on non-POSIX host"

      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("symlinks") {
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
      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("json") {
      def check(quick: Boolean) = withTmpDir { outDir =>
        PathRef.outPathOverride.withValue(Some(outDir)) {
          withTmpDir { tmpDir =>
            val file = tmpDir / "foo.txt"
            os.write(file, "hello")
            val pr = PathRef(file, quick)
            val prFile = pr.path.toString().replace("\\", "\\\\")
            val json = upickle.write(pr)
            if (quick) {
              assert(json.startsWith(""""qref:v0:"""))
              assert(json.endsWith(s""":${prFile}""""))
            } else {
              val hash = if (Properties.isWin) "86df6a6a" else "4c7ef487"
              val expected = s""""ref:v0:${hash}:${prFile}""""
              assert(json == expected)
            }
            val pr1 = upickle.read[PathRef](json)
            assert(pr == pr1)
          }
        }
      }

      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("encode") {
      withTmpDir { tmpDir =>
        val workspaceDir = tmpDir / "workspace"
        BuildCtx.workspaceRoot0.withValue(workspaceDir) {
          val outDir = workspaceDir / "out"
          PathRef.outPathOverride.withValue(Some(outDir)) {

            def check(file: os.Path, contains: Seq[String], containsNot: Seq[String]) = {
              val pr = PathRef(file)
              val enc = PathRef.encodeKnownRoots(pr)
              val dec = PathRef.decodeKnownRoots(enc)
              assert(pr.toString == dec)
              contains.foreach(s => enc.containsSlice(s))
              containsNot.foreach(s => !enc.containsSlice(s))

              file -> enc
            }

            val file1 = tmpDir / "file1"
            val file2 = workspaceDir / "file2"
            val file3 = outDir / "file3"

            assert(
              PathRef.encodeKnownRoots(PathRef(file1)).containsSlice("ref:"),
              !PathRef.encodeKnownRoots(PathRef(file1)).containsSlice("$WORKSPACE"),
              !PathRef.encodeKnownRoots(PathRef(file1)).containsSlice("$MILL_OUT"),
              PathRef.encodeKnownRoots(PathRef(file2)).containsSlice("$WORKSPACE/file2"),
              !PathRef.encodeKnownRoots(PathRef(file2)).containsSlice("$MILL_OUT"),
              PathRef.encodeKnownRoots(PathRef(file3)).containsSlice("$MILL_OUT/file3"),
              !PathRef.encodeKnownRoots(PathRef(file3)).containsSlice("WORKSPACE")
            )

            Seq(
              "mapping" -> PathRef.knownRoots,
              check(file1, Seq("ref:v0:", file1.toString), Seq("$WORKSPACE", "$MILL_OUT")),
                check (file2, Seq("ref:v0:", "$WORKSPACE/file2"), Seq("$MILL_OUT")),
                check (file3, Seq("ref:v0:", "$MILL_OUT/file3"), Seq("$WORKSPACE")),
            )
          }
        }
      }
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
