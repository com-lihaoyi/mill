package foo

import mainargs.{main, Parser}

object Foo {
  @main
  def main(text: String): Unit = {
    println("text: " + text)
    println("MyDeps.value: " + MyDeps.value)
    println("my.line.count: " + sys.props("my.line.count"))
  }

  def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
}
