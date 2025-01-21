package foo

open class Foo {
    fun hello(): String = "Hello World"
}

fun main(args: Array<String>) = println(Foo().hello())
