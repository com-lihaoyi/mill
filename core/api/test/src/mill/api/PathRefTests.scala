package mill.api

import utest.*

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Properties
import mill.api.JsonFormatters.*

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
      def check(quick: Boolean) = withTmpDir { tmpDir =>
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

        val colonFile = tmpDir / "foo:bar.txt"
        os.write(colonFile, "hello")
        val colonPathRef = PathRef(colonFile, quick)
        assert(upickle.read[PathRef](upickle.write(colonPathRef)) == colonPathRef)
      }

      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("toRelStringUsesAliasesForSubprocessCwd") - withTmpDir { workspace =>
      val homePath = os.home / "cache" / "dep.jar"
      val workspacePath = workspace / "foo" / "src" / "Main.java"

      assert(
        PathRef.toRelString(
          workspacePath,
          workspace,
          workspace
        ) == "out/mill-workspace/foo/src/Main.java",
        PathRef.toRelString(homePath, workspace, workspace) == "out/mill-home/cache/dep.jar"
      )

      val taskDest = workspace / "out" / "foo" / "run.dest"
      assert(
        PathRef.toRelString(
          workspacePath,
          taskDest,
          workspace
        ) == "../mill-workspace/foo/src/Main.java",
        PathRef.toRelString(homePath, taskDest, workspace) == "../mill-home/cache/dep.jar"
      )

      val nestedCwd = taskDest / "sub" / "dir"
      assert(
        PathRef.toRelString(
          workspacePath,
          nestedCwd,
          workspace
        ) == "../../../mill-workspace/foo/src/Main.java",
        PathRef.toRelString(homePath, nestedCwd, workspace) == "../../../mill-home/cache/dep.jar"
      )

      val sourceCwd = workspace / "foo"
      assert(
        PathRef.toRelString(
          workspacePath,
          sourceCwd,
          workspace
        ) == PathRef.toAbsString(workspacePath),
        PathRef.toRelString(homePath, sourceCwd, workspace) == PathRef.toAbsString(homePath)
      )
    }

    test("osPathJsonUsesActivePathSerializer") - withTmpDir { workspace =>
      val file = workspace / "out" / "compile.dest" / "zinc"

      object WorkspaceAliasSerializer extends os.Path.Serializer {
        private def toRelWorkspacePath(p: os.Path): String =
          if (p.startsWith(workspace))
            (os.up / "mill-workspace" / p.subRelativeTo(workspace)).toString
          else p.wrapped.toString

        private def deserializeString(s: String): java.nio.file.Path =
          if (s == "../mill-workspace") workspace.wrapped
          else if (s.startsWith("../mill-workspace/")) {
            (workspace / os.RelPath(s.stripPrefix("../mill-workspace/"))).wrapped
          } else os.Path.defaultPathSerializer.deserialize(s)

        def serializeString(p: os.Path): String = toRelWorkspacePath(p)
        def serializeFile(p: os.Path): java.io.File = java.io.File(toRelWorkspacePath(p))
        def serializePath(p: os.Path): java.nio.file.Path =
          java.nio.file.Paths.get(toRelWorkspacePath(p))
        def deserialize(s: String): java.nio.file.Path = deserializeString(s)
        def deserialize(s: java.io.File): java.nio.file.Path = deserializeString(s.toString)
        def deserialize(s: java.nio.file.Path): java.nio.file.Path = deserializeString(s.toString)
        def deserialize(s: java.net.URI): java.nio.file.Path =
          os.Path.defaultPathSerializer.deserialize(s)
      }

      os.Path.pathSerializer.withValue(WorkspaceAliasSerializer) {
        val json = upickle.write(file)
        val decoded = upickle.read[os.Path](json)
        assert(
          json == "\"../mill-workspace/out/compile.dest/zinc\"",
          decoded == file
        )
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
