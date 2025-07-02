package foo
import scalatags.Text.all.*
import scala.scalajs.js
object Foo {
  def main(args: Array[String]): Unit = {
    println(hello())

    val parsedJsonStr = js.JSON.parse("""{"helloworld": ["hello", "world", "!"]}""")
    val stringifiedJsObject = js.JSON.stringify(parsedJsonStr.helloworld)
    println("stringifiedJsObject: " + stringifiedJsObject)
  }

  def hello(): String = h1("Hello World").toString
}
