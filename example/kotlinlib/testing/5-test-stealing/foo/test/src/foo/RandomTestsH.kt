package foo

import org.junit.jupiter.api.Test

class RandomTestsH : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Haven", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Iris", 18)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Jazz", 20)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Kit", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Lake", 21)
    }
} 
