package app

object App {
  def main(args: Array[String]): Unit = {
    println(models.Foo("hello").greet)
  }
}
