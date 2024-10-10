package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import fansi._

object Foo2 {

  def generateHtml(text: String): CString = {
    val colored = Console.RED + "<h1>" + text + "</h1>" + Console.RESET + "\n"

    implicit val z: Zone = Zone.open
    val cResult = toCString(colored)
    z.close()
    cResult
  }

  val value = generateHtml("hello2")

  def main(args: Array[String]): Unit = {
    stdio.printf("Foo2.value: %s", Foo2.value)
    stdio.printf("Foo.value: %s", Foo.value)

    stdio.printf("FooA.value: %s", FooA.value)
    stdio.printf("FooB.value: %s", FooB.value)
    stdio.printf("FooC.value: %s", FooC.value)

    println("MyResource: " + os.read(os.resource / "MyResource.txt"))
    println("MyOtherResource: " + os.read(os.resource / "MyOtherResource.txt"))
  }
  
}
