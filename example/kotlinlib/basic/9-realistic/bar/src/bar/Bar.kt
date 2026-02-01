package bar

import kotlinx.html.p
import kotlinx.html.stream.createHTML

object Bar {
    fun value() = createHTML(prettyPrint = false).p { text("world") }.toString()
}
