package foo

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import foo.main as fooMain
import kotlinx.html.h1
import kotlinx.html.stream.createHTML

class Foo : CliktCommand() {
    val text by option("--text").required()

    override fun run() {
        println(createHTML(prettyPrint = false).h1 { text(text) }.toString())
    }
}

fun main(args: Array<String>) = Foo().main(args)
