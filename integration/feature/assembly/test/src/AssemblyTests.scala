package mill.integration.local

import mill.api.IO
import mill.integration.IntegrationTestSuite
import mill.util.{Jvm, Util}
import utest._

// Ensure the assembly is runnable, even if we have assembled lots of dependencies into it
// Reproduction of issues:
// - https://github.com/com-lihaoyi/mill/issues/528
// - https://github.com/com-lihaoyi/mill/issues/2650

object AssemblyTests extends IntegrationTestSuite {

  /**
   * Unpacks the given `src` path into the context specific destination directory.
   *
   * @param src  The ZIP file
   * @param dest The relative output folder under the context specifix destination directory.
   * @param ctx  The target context
   * @return The [[PathRef]] to the unpacked folder.
   */
  def unpackZip(src: os.Path, dest: os.Path): os.Path = {

    val byteStream = os.read.inputStream(src)
    val zipStream = new java.util.zip.ZipInputStream(byteStream)
    while ({
      zipStream.getNextEntry match {
        case null => false
        case entry =>
          if (!entry.isDirectory) {
            val entryDest = dest / os.RelPath(entry.getName)
            os.makeDir.all(entryDest / os.up)
            val fileOut = new java.io.FileOutputStream(entryDest.toString)
            IO.stream(zipStream, fileOut)
            fileOut.close()
          }
          zipStream.closeEntry()
          true
      }
    }) ()
    dest
  }

  def tests: Tests = Tests {

    def checkAssembly(
        targetSegments: Seq[String],
        checkExe: Boolean = false,
        checkTmp: Boolean = false
    ): Unit = {
      val workspacePath = initWorkspace()
      val targetName = "assembly"
      assert(eval(targetSegments.mkString(".") + "." + targetName))

      if (checkTmp) {
        val tmpFile = workspacePath / "out" / targetSegments / targetName / "out-tmp.jar"
        val unpackDir = os.temp.dir(
          dir = workspacePath / "out" / targetSegments / s"${targetName}.dest",
          prefix = "out-tmp.jar",
          deleteOnExit = false
        )
        assert(os.exists(tmpFile))
        println(s"Tmp File size: ${os.stat(tmpFile).size}")
        val unpacked = unpackZip(tmpFile, unpackDir)
        assert(os.exists(unpacked / "ultra" / "Main.class"))
      }

      val assemblyFile = workspacePath / "out" / targetSegments / s"${targetName}.dest" / "out.jar"
      assert(os.exists(assemblyFile))
      println(s"File size: ${os.stat(assemblyFile).size}")

//      val unpackDir = os.temp.dir(dir = workspacePath / "out" / targetSegments.dropRight(1) / targetSegments.takeRight(1).map(_ + ".dest"), prefix = "out.jar",deleteOnExit = false)
//      val unpacked = unpackZip(assemblyFile, unpackDir)
//      assert(os.exists(unpacked / "ultra" / "Main.class"))
      Jvm.runSubprocess(
        commandArgs = Seq(Jvm.javaExe, "-jar", assemblyFile.toString(), "--text", "tutu"),
        envArgs = Map.empty[String, String],
        workingDir = workspacePath
      )

      if (checkExe) {
        Jvm.runSubprocess(
          commandArgs = Seq(assemblyFile.toString(), "--text", "tutu"),
          envArgs = Map.empty[String, String],
          workingDir = workspacePath
        )
      }
    }

    test("Assembly") {
      test("noExe") {
        test("small") {
          checkAssembly(Seq("noExe", "small"))
        }
        test("large") {
          checkAssembly(Seq("noExe", "large"))
        }
      }
      test("exe") {
        test("small") {
          checkAssembly(Seq("exe", "small"), checkExe = true)
        }
        test("large") {
          checkAssembly(Seq("exe", "large"), checkExe = true)
        }
      }
    }
  }
}
