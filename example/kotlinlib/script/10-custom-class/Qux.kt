//| extends: millbuild.LineCountKotlinModule
//| kotlinVersion: 2.2.20
package qux

fun getLineCount(): String =
    ::main.javaClass.classLoader
        .getResourceAsStream("line-count.txt")
        .readAllBytes()
        .toString(Charsets.UTF_8)

fun main() {
    println("Line Count: " + getLineCount())
}
