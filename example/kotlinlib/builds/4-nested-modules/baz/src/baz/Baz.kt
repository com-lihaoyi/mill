package baz

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import foo.main as fooMain
import kotlinx.html.p
import kotlinx.html.stream.createHTML

class Baz: CliktCommand() {
    val barText by option("--bar-text").required()
    val quxText by option("--qux-text").required()
    val fooText by option("--foo-text").required()
    val bazText by option("--baz-text").required()

    override fun run() {
        main(barText, quxText, fooText, bazText)
    }
}

fun main(barText: String, quxText: String, fooText: String, bazText: String) {
    fooMain(fooText, barText, quxText)

    val value = createHTML(prettyPrint = false).p { text(bazText)  }.toString()
    println("Baz.value: $value")
}

fun main(args: Array<String>) = Baz().main(args)
