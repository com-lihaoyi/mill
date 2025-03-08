package foo

object Foo {
    @JvmStatic
    fun main(args: Array<String>) {
        println(greet("World"))
    }

    @JvmStatic
    fun greet(name: String): String = "Hello $name"
}
