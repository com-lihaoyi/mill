package foo

object Foo {
  val value = os.read(os.resource / "snippet.txt")
  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)
  }
}
