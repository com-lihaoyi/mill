package foo

import org.junit.jupiter.api.Test

class RandomTestsI : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Mars", 16)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("North", 12)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Onyx", 14)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Phoenix", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Quest", 13)
    }

    @Test
    @Throws(Exception::class)
    fun test6() {
        testGreeting("Rain", 11)
    }

    @Test
    @Throws(Exception::class)
    fun test7() {
        testGreeting("Sky", 17)
    }
}
