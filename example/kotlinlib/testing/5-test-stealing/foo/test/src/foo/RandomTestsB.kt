package foo

import org.junit.jupiter.api.Test

class RandomTestsB : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Dakota", 18)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Ethan", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Felix", 12)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Gabriel", 16)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Harper", 20)
    }

    @Test
    @Throws(Exception::class)
    fun test6() {
        testGreeting("Isaac", 14)
    }
}
