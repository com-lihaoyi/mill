package app

object App {
  val foo = otherpackage.Foo("foo")
  def main(args: Array[String]): Unit = println(s"Hello $foo!")
}
