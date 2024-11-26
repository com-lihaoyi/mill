package foo

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class Foo : CliktCommand() {
    val text by option("-t", "--text", help = "input text").required()

    override fun run() {
        echo("text: $text")
        echo("MyDeps.value: ${MyDeps.VALUE}")
        echo("my.line.count: ${System.getProperty("my.line.count")}")
    }
}

fun main(args: Array<String>) = Foo().main(args)
