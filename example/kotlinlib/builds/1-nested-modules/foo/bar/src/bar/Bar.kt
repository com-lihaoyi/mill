package foo.bar

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.html.h1
import kotlinx.html.stream.createHTML

class Bar: CliktCommand() {
    val text by option("--text").required()

    override fun run() {
        main(text)
    }
}

fun main(text: String) {
    val value = createHTML(prettyPrint = false).h1 { text(text)  }.toString()
    println("Bar.value: $value")
}

fun main(args: Array<String>) = Bar().main(args)
