package foo

import org.junit.jupiter.api.Test

class RandomTestsF : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Winter", 12)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Xander", 10)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Yara", 13)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Zephyr", 9)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Atlas", 11)
    }

    @Test
    @Throws(Exception::class)
    fun test6() {
        testGreeting("Blair", 14)
    }

    @Test
    @Throws(Exception::class)
    fun test7() {
        testGreeting("Cruz", 10)
    }

    @Test
    @Throws(Exception::class)
    fun test8() {
        testGreeting("Dawn", 12)
    }

    @Test
    @Throws(Exception::class)
    fun test9() {
        testGreeting("Echo", 8)
    }
} 
