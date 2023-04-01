package foo

object Foo {
  val value = new String(getClass.getResourceAsStream("/snippet.txt").readAllBytes())
  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)
  }
}
