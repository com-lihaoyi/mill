package mill.api

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.FileTime
import java.time.Instant
import utest._
import mill.api.JsonFormatters
import upickle.default._

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
      // test("qref") - check(quick = true)
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
        val json = write(pr)(PathRef.jsonFormatter)
        if (quick) {
          assert(json.startsWith(""""qref:v0:"""))
          assert(json.endsWith(s""":${prFile}""""))
        } else {
          val hash = if (Properties.isWin) "86df6a6a" else "4c7ef487"
          val expected = s""""ref:v0:${hash}:${prFile}""""
          assert(json == expected)
        }
        val pr1 = read[PathRef](json)(PathRef.jsonFormatter)
        assert(pr == pr1)
      }

      test("qref") - check(quick = true)
      test("ref") - check(quick = false)
    }

    test("path-normalization") {
      // Define some test paths
      val testUserHome = os.Path("/Users/testuser")
      val workspaceRoot = testUserHome / "projects" / "myproject"
      val coursierCache = testUserHome / ".coursier" / "cache"
      val home = testUserHome

      test("PathRef") {
        test("workspace-path") {
          val path = workspaceRoot / "src" / "main" / "scala"
          val pathRef = PathRef(path, quick = false)
          val serialized = PathRef.withSerialization {
            write(pathRef)(PathRef.jsonFormatter)
          }
          println(s"Debug: serialized = $serialized")
          assert(serialized.contains("$WORKSPACE/src/main/scala"))

          val deserialized = read[PathRef](serialized)(PathRef.jsonFormatter)
          assert(deserialized.path == path)
        }

        test("coursier-cache-path") {
          val path = coursierCache / "v1" / "https" / "repo1.maven.org" / "maven2"
          val pathRef = PathRef(path, quick = false)
          val serialized = PathRef.withSerialization {
            write(pathRef)(PathRef.jsonFormatter)
          }
          println(s"Debug: serialized = $serialized")
          assert(serialized.contains("$COURSIER_CACHE/v1/https/repo1.maven.org/maven2"))

          val deserialized = read[PathRef](serialized)(PathRef.jsonFormatter)
          assert(deserialized.path == path)
        }

        test("home-directory-path") {
          val path = home / "documents" / "project"
          val pathRef = PathRef(path, quick = false)
          val serialized = PathRef.withSerialization {
            write(pathRef)(PathRef.jsonFormatter)
          }
          println(s"Debug: serialized = $serialized")
          assert(serialized.contains("$HOME/documents/project"))

          val deserialized = read[PathRef](serialized)(PathRef.jsonFormatter)
          assert(deserialized.path == path)
        }

        test("non-special-path") {
          val path = os.Path("/tmp/someproject")
          val pathRef = PathRef(path, quick = false)
          val serialized = write(pathRef)(PathRef.jsonFormatter)
          assert(serialized.contains("/tmp/someproject"))

          val deserialized = read[PathRef](serialized)(PathRef.jsonFormatter)
          assert(deserialized.path == pathRef.path)
        }
      }

      test("JsonFormatters") {
        test("workspace-path") {
          val path = workspaceRoot / "src" / "main" / "scala"
          val serialized = write(path)(JsonFormatters.pathReadWrite)
          println(s"Debug: serialized = $serialized")
          assert(serialized == "\"$WORKSPACE/src/main/scala\"")

          val deserialized = read[os.Path](serialized)(JsonFormatters.pathReadWrite)
          assert(deserialized == path)
        }

        test("coursier-cache-path") {
          val path = coursierCache / "v1" / "https" / "repo1.maven.org" / "maven2"
          val serialized = write(path)(JsonFormatters.pathReadWrite)
          assert(serialized == "\"$COURSIER_CACHE/v1/https/repo1.maven.org/maven2\"")

          val deserialized = read[os.Path](serialized)(JsonFormatters.pathReadWrite)
          assert(deserialized == path)
        }

        test("home-directory-path") {
          val path = home / "documents" / "project"
          val serialized = write(path)(JsonFormatters.pathReadWrite)
          assert(serialized == "\"$HOME/documents/project\"")

          val deserialized = read[os.Path](serialized)(JsonFormatters.pathReadWrite)
          assert(deserialized == path)
        }

        test("non-special-path") {
          val path = os.Path("/tmp/someproject")
          val serialized = write(path)(JsonFormatters.pathReadWrite)
          assert(serialized == "\"/tmp/someproject\"")

          val deserialized = read[os.Path](serialized)(JsonFormatters.pathReadWrite)
          assert(deserialized == path)
        }
      }
    }

    test("non-deterministic-files") {
      val testUserHome = os.Path("/Users/testuser")
      val workspaceRoot = testUserHome / "projects" / "myproject"

      test("mill-server-file") {
        val path = workspaceRoot / "out" / "mill-server" / "some-file.txt"
        val pathRef = PathRef(path, quick = false)
        val serialized = write(pathRef)(PathRef.jsonFormatter)
        println(s"Debug: serialized = $serialized")
        assert(serialized.contains("$NON_DETERMINISTIC"))
      }

      test("worker-json-file") {
        val path = workspaceRoot / "out" / "worker.json"
        val pathRef = PathRef(path, quick = false)
        val serialized = PathRef.withSerialization {
          write(pathRef)(PathRef.jsonFormatter)
        }
        println(s"Debug: Original path = $path")
        println(s"Debug: Normalized path = ${NonDeterministicFiles.normalizeWorkerJson(path)}")
        println(s"Debug: serialized = $serialized")
        assert(serialized.contains("worker.worker.json"))
      }

      test("zip-file-modification-time") {
        withTmpDir { tmpDir =>
          val zipFile = tmpDir / "test.zip"
          os.write(zipFile, "test content")
          val originalTime = os.mtime(zipFile)
          zeroOutModificationTime(zipFile)
          val newTime = os.mtime(zipFile)
          println(s"Debug: originalTime = $originalTime, newTime = $newTime")
          assert(newTime < originalTime)
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

  private def zeroOutModificationTime(path: os.Path): Unit = {
    val zeroTime = FileTime.from(Instant.EPOCH)
    Files.setLastModifiedTime(path.toNIO, zeroTime)
  }
}
