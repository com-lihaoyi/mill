package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import fansi._

object Foo2 {

  def generateHtml(text: String): CString = {
    val colored = Console.RED + "<h1>" + text + "</h1>" + Console.RESET

    implicit val z: Zone = Zone.open
    val cResult = toCString(colored)
    z.close()
    cResult
  }

  val value = generateHtml("hello2")

  def main(args: Array[String]): Unit = {
    stdio.printf(c"Foo2.value: %s\n", Foo2.value)
    stdio.fflush(null)
    stdio.printf(c"Foo.value: %s\n", Foo.value)
    stdio.fflush(null)

    implicit val z: Zone = Zone.open
    val cFooA = toCString(FooA.value)
    val cFooB = toCString(FooB.value)
    val cFooC = toCString(FooC.value)
    z.close

    stdio.printf(c"FooA.value: %s\n", cFooA)
    stdio.fflush(null)
    stdio.printf(c"FooB.value: %s\n", cFooB)
    stdio.fflush(null)
    stdio.printf(c"FooC.value: %s\n", cFooC)
    stdio.fflush(null)

    val myResource = os.read(os.pwd / "resources" / "MyResource.txt")
    val myOtherResource = os.read(os.pwd / "common-resources" / "MyOtherResource.txt")

    println("MyResource: " + myResource)
    println("MyOtherResource: " + myOtherResource)

  }
  
}

