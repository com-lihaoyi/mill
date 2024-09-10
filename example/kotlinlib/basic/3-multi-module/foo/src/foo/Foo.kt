package foo

import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.inf.ArgumentParser
import net.sourceforge.argparse4j.inf.Namespace

const val VALUE: String = "hello"

fun mainFunction(fooText: String, barText: String) {
    println("Foo.value: " + VALUE)
    println("Bar.value: " + bar.generateHtml(barText))
}

fun main(args: Array<String>) {
    val parser = ArgumentParsers.newFor("Foo").build()
    parser.addArgument("--foo-text").required(true)
    parser.addArgument("--bar-text").required(true)

    val res = parser.parseArgs(args)

    val fooText = res.getString("foo_text")
    val barText = res.getString("bar_text")

    mainFunction(fooText, barText)
}
