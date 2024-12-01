package mill.main.client

import utest._
import java.io.File
import scala.io.Source
import java.nio.file.Paths

object MillEnvTests extends TestSuite {

  val tests = Tests {
    // Get the file from resources
    assert(1 == 1)

    println("OMG THIS IS HORRIBLE I DON'T KNOW HOW TO SOLVE THIS IS SCALA NATIVE")
    val resourcePath =
      Paths.get("/Users/simon/Code/mill-1/main/client/test/resources/file-wo-final-newline.txt")
    // val specificFilePath = resourcePath.resolve("file-wo-final-newline.txt").toAbsolutePath.toString

    // println(specificFilePath)

    val file = new File(resourcePath.toUri())
    // val file = new File(getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI)

    // // Read lines using the Util method
    val lines = Util.readOptsFileLines(file)

    println(lines)

    // // Assert the expected content
    assert("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file" == lines(0))
    assert("-Xss120m" == lines(1))
  }
}
