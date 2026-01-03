package foo
import scalatags.Text.all._
object Foo2 {
  val value = h1("hello2")
  def main(args: Array[String]): Unit = {
    println("Foo2.value: " + Foo2.value)
    println("Foo.value: " + Foo.value)

    println("FooA.value: " + FooA.value)
    println("FooB.value: " + FooB.value)
    println("FooC.value: " + FooC.value)

    println("MyResource: " + os.read(os.resource / "MyResource.txt"))
    println("MyOtherResource: " + os.read(os.resource / "MyOtherResource.txt"))

    println("my.custom.property: " + sys.props("my.custom.property"))

    if (sys.env.contains("MY_CUSTOM_ENV")) println("MY_CUSTOM_ENV: " + sys.env("MY_CUSTOM_ENV"))
  }
}
