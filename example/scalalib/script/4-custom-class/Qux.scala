//| extends: [millbuild.LineCountScalaModule]
//| scalaVersion: 3.7.3
package qux

object Qux {

  def getLineCount() = {
    scala.io.Source
      .fromResource("line-count.txt")
      .mkString
  }

  val lineCount = getLineCount()

  def main(args: Array[String]): Unit = {
    println(s"Line Count: $lineCount")
  }

}
