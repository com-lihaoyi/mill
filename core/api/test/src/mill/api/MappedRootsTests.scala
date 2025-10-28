package mill.api

import utest.*

import java.nio.file.Files
import mill.api.{MappedRoots => MR}

object MappedRootsTests extends TestSuite {
  val tests: Tests = Tests {
    test("encode") {
      withTmpDir { tmpDir =>
        val workspaceDir = tmpDir / "workspace"
        val outDir = workspaceDir / "out"
        MR.withMillDefaults(outPath = outDir, workspacePath = workspaceDir) {

          def check(path: os.Path, encContains: Seq[String], containsNot: Seq[String]) = {
            val enc = MR.encodeKnownRootsInPath(path)
            val dec = MR.decodeKnownRootsInPath(enc)
            assert(path.toString == dec)
            encContains.foreach(s => assert(enc.containsSlice(s)))
            containsNot.foreach(s => assert(!enc.containsSlice(s)))

            path -> enc
          }

          val file1 = tmpDir / "file1"
          val file2 = workspaceDir / "file2"
          val file3 = outDir / "file3"

          Seq(
            "mapping" -> MR.get,
            check(file1, Seq(file1.toString), Seq("$WORKSPACE", "$MILL_OUT")),
            check(file2, Seq("$WORKSPACE/file2"), Seq("$MILL_OUT")),
            check(file3, Seq("$MILL_OUT/file3"), Seq("$WORKSPACE"))
          )
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

}
