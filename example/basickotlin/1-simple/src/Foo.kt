package foo

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import kotlinx.cli.*

object Foo {

  fun generateHtml(text: String): String {
    return createHTML().h1 { +text }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val parser = ArgParser("Foo")
    val text by parser.option(ArgType.String, shortName = "t", description = "Text to include in H1").required()
    parser.parse(args)

    println(generateHtml(text))
  }
}