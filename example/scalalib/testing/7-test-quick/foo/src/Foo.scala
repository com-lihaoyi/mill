package foo

import foo.Bar

object Foo {
  def main(args: Array[String]): Unit = {
    println(Bar.greet("World"))
  }

  def greet(name: String): String = Bar.greet(name)

  def greet2(name: String): String = Bar.greet2(name)
}
