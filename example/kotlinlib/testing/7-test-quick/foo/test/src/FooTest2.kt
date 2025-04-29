package foo

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FooTest2 {
    @Test
    fun test2() {
        val name = "Bob"
        val greeted = Foo.greet2(name)
        assertEquals("Hi, $name!", greeted)
    }
}