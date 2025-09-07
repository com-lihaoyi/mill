//| mvnDeps:
//| - com.lihaoyi::scalatags:0.13.1
//| - com.lihaoyi::mainargs:0.7.6
package foo
import scalatags.Text.all.*
import mainargs.{main, ParserForMethods}

object Foo {
  def generateHtml(text: String) = {
    h1(text).toString
  }

  @main
  def main(text: String) = {
    println(generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
