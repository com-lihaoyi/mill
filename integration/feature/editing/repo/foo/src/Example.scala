package foo

object Example {
  def main(args: Array[String]): Unit = {
    println(sys.env("snippet") + "!")
  }
}
