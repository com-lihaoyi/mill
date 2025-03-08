package foo

import org.junit.jupiter.api.Test

class RandomTestsA : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Storm", 38)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Bella", 25)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Cameron", 32)
    }
}
