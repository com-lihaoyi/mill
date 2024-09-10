package bar

import org.apache.commons.text.StringEscapeUtils
import net.sourceforge.argparse4j.ArgumentParsers
import net.sourceforge.argparse4j.inf.ArgumentParser
import net.sourceforge.argparse4j.inf.Namespace

fun generateHtml(text: String): String {
    return "<h1>" + StringEscapeUtils.escapeHtml4("world") + "</h1>"
}

fun main(args: Array<String>) {
    println("Bar.value: " + generateHtml(args[0]))
}