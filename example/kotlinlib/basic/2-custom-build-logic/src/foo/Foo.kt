package foo

import java.io.IOException

fun getLineCount(): String? {
    return try {
        String(
            ::main.javaClass.classLoader.getResourceAsStream("line-count.txt").readAllBytes()
        )
    } catch (e: IOException) {
        null
    }
}

fun main() {
    println("Line Count: " + getLineCount())
}
