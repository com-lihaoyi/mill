package foo
object Foo {
  def main(args: Array[String]): Unit = {
    val conf = os.read(os.resource / "application.conf")
    println("Loaded application.conf from resources: " + conf)
    println("Loaded test.property: " + System.getProperty("test.property"))
  }
}
