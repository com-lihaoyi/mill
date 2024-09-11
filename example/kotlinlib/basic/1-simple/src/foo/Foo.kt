package foo

import org.apache.commons.text.StringEscapeUtils
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class Foo: CliktCommand() {
    val text by option("-t", "--text", help="text to insert").required()

    override fun run() {
        echo(generateHtml(text))
    }
}

fun generateHtml(text: String): String {
    return "<h1>" + StringEscapeUtils.escapeHtml4(text) + "</h1>"
}

fun main(args: Array<String>) = Foo().main(args)
