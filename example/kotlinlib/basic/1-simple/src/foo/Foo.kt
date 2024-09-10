package foo

import org.apache.commons.text.StringEscapeUtils
import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.inf.ArgumentParser
import net.sourceforge.argparse4j.inf.ArgumentParserException
import net.sourceforge.argparse4j.inf.Namespace

fun generateHtml(text: String): String {
    return "<h1>" + StringEscapeUtils.escapeHtml4(text) + "</h1>"
}

fun main(args: Array<String>) {
    val parser = ArgumentParsers.newFor("template").build()
        .defaultHelp(true)
        .description("Inserts text into a HTML template")

    parser.addArgument("-t", "--text")
        .required(true)
        .help("text to insert")

    try {
        parser.parseArgs(args).let {
            println(generateHtml(it.getString("text")))
        }
    } catch (e: Exception) {
        println(e.message)
        System.exit(1)
    }
}
