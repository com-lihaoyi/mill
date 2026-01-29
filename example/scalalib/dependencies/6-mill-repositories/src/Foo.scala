package foo

object Foo {
  def main(args: Array[String]): Unit = {
    println(s"text: $text, Scala version: ${scala.util.Properties.versionString}")
  }
}
