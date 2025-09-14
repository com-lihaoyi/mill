//| moduleDeps: ["bar"]
//| mvnDeps: ["com.github.ajalt.clikt:clikt:4.4.0"]
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required


class Foo : CliktCommand() {
    val text by option("--text").required()
    override fun run() {
        println(bar.generateHtml(text))
    }
}

fun main(args: Array<String>) = Foo().main(args)
