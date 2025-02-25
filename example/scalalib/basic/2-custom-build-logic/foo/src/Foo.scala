package foo

object Foo {

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
