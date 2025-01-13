package foo

import kotlinx.html.h1
import kotlinx.html.stream.createHTML
import java.io.IOException

fun main(args: Array<String>) {
    println("Foo2.value: ${Foo2.VALUE}")
    println("Foo.value: ${Foo.VALUE}")

    println("FooA.value: ${FooA.VALUE}")
    println("FooB.value: ${FooB.VALUE}")
    println("FooC.value: ${FooC.VALUE}")

    println("MyResource: ${readResource("MyResource.txt")}")
    println("MyOtherResource: ${readResource("MyOtherResource.txt")}")

    val properties = System.getProperties()
    println("my.custom.property: ${properties.getProperty("my.custom.property")}")

    val myCustomEnv = System.getenv("MY_CUSTOM_ENV")
    if (myCustomEnv != null) {
        println("MY_CUSTOM_ENV: $myCustomEnv")
    }
}

object Foo2 {
    val VALUE = createHTML(prettyPrint = false).h1 { text("hello2") }.toString()
}

private fun readResource(resourceName: String): String? = try {
    ::main.javaClass.classLoader
        .getResourceAsStream(resourceName)
        .readAllBytes()
        .toString(Charsets.UTF_8)
} catch (e: IOException) {
    null
}
