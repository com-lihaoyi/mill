package foo

import org.junit.jupiter.api.Test

class GroupY2 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Odin", 34)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Pluto", 32)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Quetzal", 33)
    }
} 
