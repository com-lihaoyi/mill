package foo
object Foo{

  def main(args: Array[String]): Unit = {
    println("enter any character: ")
    val c = System.in.read().toChar
    println("c: [" + c + "]")
  }
}
