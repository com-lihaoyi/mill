package foo.qux

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import foo.bar.main as barMain
import kotlinx.html.p
import kotlinx.html.stream.createHTML

class Qux: CliktCommand() {
    val barText by option("--bar-text").required()
    val quxText by option("--qux-text").required()

    override fun run() {
        main(barText, quxText)
    }
}

fun main(barText: String, quxText: String) {
    barMain(barText)

    val value = createHTML(prettyPrint = false).p { text(quxText)  }.toString()
    println("Qux.value: $value")
}

fun main(args: Array<String>) = Qux().main(args)
