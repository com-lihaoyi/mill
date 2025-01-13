package foo

import kotlinx.html.h1
import kotlinx.html.stream.createHTML

object Foo {
    val VALUE = createHTML(prettyPrint = false).h1 { text("hello") }.toString()
}
