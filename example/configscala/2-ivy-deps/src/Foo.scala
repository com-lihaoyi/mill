package foo

object Foo {
  def main(args: Array[String]): Unit = {
    pprint.PPrinter.BlackWhite.log(args)
    println(upickle.default.write(args))
  }
}
