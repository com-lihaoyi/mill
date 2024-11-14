package foo

import java.io.IOException
import java.io.InputStream

object Foo {

    // Read `file.txt` from classpath
    fun classpathResourceText(): String {
        // Get the resource as an InputStream
        return Foo::class.java.classLoader.getResourceAsStream("file.txt").use {
            it.readAllBytes().toString(Charsets.UTF_8)
        }
    }
}
