package foo

public class Foo {
    companion object {
        val value = "<h1>hello</h1>"

        @JvmStatic
        fun main(args: Array<String>) {
            println("Foo.value: " + Foo.value)
            println("Bar.value: " + bar.Bar.value())
            println("Qux.value: " + qux.Qux.value)
        }
    }
}
