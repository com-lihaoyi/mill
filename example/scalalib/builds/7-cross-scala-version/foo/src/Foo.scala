package foo
object Foo {
  def main(args: Array[String]): Unit = {
    println("Foo.value: " + foo.Foo.value)
    println("Bar.value: " + bar.Bar.value)
    println(MajorVersionSpecific.text())
    println(MinorVersionSpecific.text())
  }
  val value = "Hello World " + scala.util.Properties.versionMsg
}
