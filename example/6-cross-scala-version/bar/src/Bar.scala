package bar
object Bar {
  val value = "bar-value"

  def main(args: Array[String]): Unit = {
    println("Bar.value: " + bar.Bar.value)
    println("Foo.value: " + foo.Foo.value)
  }
}
