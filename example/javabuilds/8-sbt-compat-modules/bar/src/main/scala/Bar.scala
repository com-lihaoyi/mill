package bar
object Bar {
  def main(args: Array[String]): Unit = {
    println("Bar.value: " + bar.Bar.value)
    println(MinorVersionSpecific.text())
  }
  val value = "Hello World " + scala.util.Properties.versionMsg
}
