package foo

object Foo {
  def main(args: Array[String]): Unit = {
    println(s"Scala version: ${scala.util.Properties.versionString}")
  }
}
