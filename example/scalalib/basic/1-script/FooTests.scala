//| moduleDeps: [Foo.scala]
//| mvnDeps:
//| - "com.google.guava:guava:33.3.0-jre"
import com.google.common.html.HtmlEscapers.htmlEscaper
def main(args: Array[String]): Unit = {
  val result = generateHtml("hello")
  assert(result == "<h1>hello</h1>")
  println(result)
  val result2 = generateHtml("<hello>")
  val expected2 = "<h1>" + htmlEscaper().escape("<hello>") + "</h1>"
  assert(result2 == expected2)
  println(result2)
}
