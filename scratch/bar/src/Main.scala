import foo.Library

object HelloWorld {
  def main(args: Array[String]): Unit = {
    println(s"Hello, world! ${Library.fancyLibrary}")
  }
}
