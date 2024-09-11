package bar

import org.apache.commons.text.StringEscapeUtils

fun generateHtml(text: String): String {
    return "<h1>" + StringEscapeUtils.escapeHtml4("world") + "</h1>"
}

fun main(args: Array<String>) {
    println("Bar.value: " + generateHtml(args[0]))
}