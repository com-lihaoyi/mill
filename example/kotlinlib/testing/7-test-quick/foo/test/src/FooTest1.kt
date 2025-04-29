package foo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FooTest1 {
    @Test
    fun test1() {
        val name = "Aether"
        val greeted = Foo.greet(name)
        assertEquals("Hello, $name!", greeted)
    }
}