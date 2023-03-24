
package mill.integration

import mill.util.Util
import utest._

class ParseErrorTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("parse-error", fork, clientServer) {
  val tests = Tests {
    initWorkspace()

    def lineNumberLookup(data: String): Array[Int] = {
      val lineStarts = new collection.mutable.ArrayBuffer[Int]()
      var i = 0
      var col = 1
      var cr = false
      var prev: Character = null
      while (i < data.length) {
        val char = data(i)
        if (char == '\r') {
          if (prev != '\n' && col == 1) lineStarts.append(i)
          col = 1
          cr = true
        } else if (char == '\n') {
          if (prev != '\r' && col == 1) lineStarts.append(i)
          col = 1
          cr = false
        } else {
          if (col == 1) lineStarts.append(i)
          col += 1
          cr = false
        }
        prev = char
        i += 1
      }
      if (col == 1) lineStarts.append(i)

      lineStarts.toArray
    }


    val barScString = os.read(os.pwd / "integration"/ "resources" / "parse-error" / "bar.sc")
    pprint.log(barScString.toCharArray)
    pprint.log(lineNumberLookup(barScString))
    test {
      val (res, out, err) = evalStdout("foo.scalaVersion")
      assert(res == false)
      val errorString = err.mkString("\n")

      assert(
        errorString.contains(
          """bar.sc:4:20 expected ")"
            |println(doesntExist})
            |                   ^""".stripMargin.linesIterator.mkString(Util.newLine)
        )
      )
      assert(
        errorString.contains(
          """qux.sc:3:31 expected ")"
            |System.out.println(doesntExist
            |                              ^""".stripMargin.linesIterator.mkString(Util.newLine)
        )
      )

    }
  }
}
