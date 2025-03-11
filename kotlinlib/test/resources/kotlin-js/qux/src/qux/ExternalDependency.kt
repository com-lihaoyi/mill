package qux

import kotlinx.html.div
import kotlinx.html.stream.createHTML

fun doThing() {
    println(createHTML().div { +"Hello" })
}
