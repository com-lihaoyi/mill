package mill.client

import utest._

import java.nio.file.Paths
import scala.jdk.CollectionConverters._

object ClientUtilTests extends TestSuite {
  val tests = Tests {
    test("readOptsFileLinesWithoutFinalNewline") {
      val file = Paths.get(
        getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI
      )
      val env = java.util.Map.of[String, String]()
      val lines = ClientUtil.readOptsFileLines(file, env).asScala.toList
      assert(lines == List("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"))
    }
  }
}
