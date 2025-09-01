package foo

object Foo {
  def main(args: Array[String]): Unit = {
    println("pretty-printed using PPrint: " + pprint.PPrinter.BlackWhite.apply(args))
    println("serialized using uPickle: " + upickle.write(args))
  }
}
