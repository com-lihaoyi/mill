package qux

object Qux {
  def getLineCount() = {
    scala.io.Source
      .fromResource("line-count.txt")
      .mkString
  }

  def main(args: Array[String]): Unit = {
    println(s"Line Count: ${getLineCount()}")
  }
}
