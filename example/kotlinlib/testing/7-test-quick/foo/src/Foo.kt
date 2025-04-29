package foo

object Foo {
    @JvmStatic
    fun main(args: Array<String>) {
        println(Bar.greet("World"))
    }

    fun greet(name: String): String {
        return Bar.greet(name)
    }

    fun greet2(name: String): String {
        return Bar.greet2(name)
    }
}