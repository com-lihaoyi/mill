package qux

import java.io.IOException

fun getLineCount(): String? = try {
    ::main.javaClass.classLoader
        .getResourceAsStream("line-count.txt")
        .readAllBytes()
        .toString(Charsets.UTF_8)
} catch (e: IOException) {
    null
}

fun main() {
    var msg = "Line Count: " + getLineCount()
    println(msg)
}
