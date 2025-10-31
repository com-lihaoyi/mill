//| resources: ["./resources"]
package foo

fun main() {
    val msg = object {}::class.java.classLoader.getResourceAsStream("file.txt")!!.use {
        it.readAllBytes().toString(Charsets.UTF_8)
    }
    println(msg)
}
