package foo

import kotlinx.html.*
import kotlinx.html.stream.createHTML

fun main() {
    println(hello())

    val parsedJsonStr: dynamic = JSON.parse("""{"helloworld": ["hello", "world", "!"]}""")
    val stringifiedJsObject = JSON.stringify(parsedJsonStr.helloworld)
    println("stringifiedJsObject: " + stringifiedJsObject)
}

fun hello(): String = createHTML(prettyPrint = false).h1 { text("Hello World") }.toString()
