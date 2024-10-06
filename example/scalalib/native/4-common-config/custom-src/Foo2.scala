package foo

import scala.scalanative.unsafe._

object Foo2 {
  val value = generateHtml(c"hello2")
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


// Define the external module, the C library containing our function "generateHtml"
@extern
@link("HelloWorld")
// Arbitrary object name
object HelloWorld {
  // Name and signature of C function
  def generateHtml(str: CString): CString = extern
}
