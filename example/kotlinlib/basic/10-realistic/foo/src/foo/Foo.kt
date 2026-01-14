package foo

object Foo {
    const val VALUE = "<h1>hello</h1>"
}

fun main() {
    println("foo version " + Version.value())
    println("Foo.value: " + Foo.VALUE)
    println("Bar.value: " + bar.Bar.value())
    println("Qux.value: " + qux.Qux.VALUE)
}
