package foo

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

const val VALUE: String = "hello"

class Foo : CliktCommand() {
    val fooText by option("--foo-text").required()
    val barText by option("--bar-text").required()

    override fun run() {
        mainFunction(fooText, barText)
    }
}

fun mainFunction(
    fooText: String,
    barText: String,
) {
    println("Foo.value: " + VALUE)
    println("Bar.value: " + bar.generateHtml(barText))
}

fun main(args: Array<String>) = Foo().main(args)
