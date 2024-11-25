package mill.main.client

import org.junit.Test
import org.junit.Assert._
import java.io.File

class MillEnvTests {

  @Test
  def readOptsFileLinesWithoutFInalNewline(): Unit = {
    // Get the file from resources
    val file = new File(getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI)

    // Read lines using the Util method
    val lines = Util.readOptsFileLines(file)

    // Assert the expected content
    assertEquals(List("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m"), lines)
  }
}
