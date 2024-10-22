package foo

import java.io.IOException

fun getLineCount(): String? {
    return try {
        ::main.javaClass.classLoader
            .getResourceAsStream("line-count.txt")
            .readAllBytes()
            .toString(Charsets.UTF_8)
    } catch (e: IOException) {
        null
    }
}

fun main() = println("Line Count: " + getLineCount())

