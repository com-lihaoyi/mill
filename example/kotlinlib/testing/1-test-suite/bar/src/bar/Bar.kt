package bar

open class Bar {
    fun hello(): String = "Hello World"
}

fun main(args: Array<String>) = println(Bar().hello())
