package foo

object Foo {
  val value = "<h1>hello</h1>"

  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)
    println("Bar.value: " + bar.Bar.value)
    println("Qux.value: " + qux.Qux.value)
  }
}
