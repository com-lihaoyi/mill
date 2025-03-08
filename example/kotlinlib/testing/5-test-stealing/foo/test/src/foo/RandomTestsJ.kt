package foo

import org.junit.jupiter.api.Test

class RandomTestsJ : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Storm", 38)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("True", 32)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Vale", 28)
    }
}
