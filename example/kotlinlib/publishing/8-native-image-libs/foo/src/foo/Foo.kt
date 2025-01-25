package foo

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.html.h1
import kotlinx.html.stream.createHTML

class Foo : CliktCommand() {
    val text by option("-t", "--text", help = "text to insert").required()

    override fun run() {
        echo(generateHtml(text))
    }
}

fun generateHtml(text: String): String = createHTML(prettyPrint = false).h1 { text(text) }.toString()

fun main(args: Array<String>) = Foo().main(args)
