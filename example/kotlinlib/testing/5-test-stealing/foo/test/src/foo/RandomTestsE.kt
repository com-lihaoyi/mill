package foo

import org.junit.jupiter.api.Test

class RandomTestsE : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Sage", 28)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Talia", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Urban", 25)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Violet", 20)
    }
} 
