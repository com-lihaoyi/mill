package mill.client

import utest._

import java.nio.file.{Path, Paths}
import scala.jdk.CollectionConverters._

object MillEnvTests extends TestSuite {

  val tests = Tests {
    test("readOptsFileLinesWithoutFinalNewline") {
      val file: Path =
        Paths.get(getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI)
      val lines: List[String] = ClientUtil.readOptsFileLines(file).asScala.toList
      assert(lines == List("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"))
    }
  }
}
