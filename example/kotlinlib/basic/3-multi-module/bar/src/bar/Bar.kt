package bar

import kotlinx.html.h1
import kotlinx.html.stream.createHTML

fun generateHtml(text: String): String {
    return createHTML(prettyPrint = false).h1 { text(text)  }.toString()
}

fun main(args: Array<String>) {
    println("Bar.value: " + generateHtml(args[0]))
}
