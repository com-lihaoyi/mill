package mill.client

import utest._

object ClientUtilTests extends TestSuite {
  val tests = Tests {
    test("readOptsFileLinesWithoutFinalNewline") {
      val file = os.Path(
        getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI
      )
      val env = Map.empty[String, String]
      val lines = ClientUtil.readOptsFileLines(file, env).toList
      assert(lines == List("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"))
    }
  }
}
