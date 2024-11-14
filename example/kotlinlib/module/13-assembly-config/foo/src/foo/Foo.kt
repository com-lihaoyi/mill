package foo

import java.io.IOException
import java.io.InputStream

fun main(args: Array<String>) {
    ::main.javaClass
        .classLoader
        .getResourceAsStream("application.conf")
        .use {
            val conf = it.readAllBytes().toString(Charsets.UTF_8)
            println("Loaded application.conf from resources: $conf")
        }

}
