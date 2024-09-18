package foo

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import foo.qux.main as quxMain
import kotlinx.html.p
import kotlinx.html.stream.createHTML

class Foo: CliktCommand() {
    val barText by option("--bar-text").required()
    val quxText by option("--qux-text").required()
    val fooText by option("--foo-text").required()

    override fun run() {
        main(fooText, barText, quxText)
    }
}

fun main(fooText: String, barText: String, quxText: String) {
    quxMain(barText, quxText)

    val value = createHTML(prettyPrint = false).p { text(fooText)  }.toString()
    println("Foo.value: $value")
}

fun main(args: Array<String>) = Foo().main(args)
