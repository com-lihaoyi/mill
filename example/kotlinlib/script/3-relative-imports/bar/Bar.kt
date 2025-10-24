//| kotlinVersion: 2.0.20
//| mvnDeps:
//| - org.jetbrains.kotlinx:kotlinx-html:0.11.0
package bar

import kotlinx.html.h1
import kotlinx.html.stream.createHTML

fun generateHtml(text: String): String = createHTML(prettyPrint = false).h1 { text(text) }.toString()

fun main(args: Array<String>) {
    println("Bar.value: " + generateHtml(args[0]))
}
