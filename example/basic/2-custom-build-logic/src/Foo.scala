package foo

object Foo{
  val lineCount = scala.io.Source
    .fromResource("line-count.txt")
    .mkString

  def main(args: Array[String]): Unit = {

    println(s"Line Count: $lineCount")
  }
}
