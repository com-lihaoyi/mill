package mill.main.client

import utest._
import java.io.File
import java.nio.file.Paths
import scala.jdk.CollectionConverters._
import mill.main.client.Util

object MillEnvTests extends TestSuite {

  val tests = Tests {
    test("readOptsFileLinesWithoutFinalNewline") {
      // val file = Paths.get(getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI).toFile
      val f = os.temp(prefix = "mill")
      val resourceFile = this.getClass().getResourceAsStream("/file-wo-final-newline.txt")
      os.write.over(f, resourceFile.readAllBytes())
      val lines = Util.readOptsFileLines(f.toIO)
      val expectedLines = List(
        "-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file",
        "-Xss120m"
      )
      assert(lines == expectedLines)
    }
  }
}
