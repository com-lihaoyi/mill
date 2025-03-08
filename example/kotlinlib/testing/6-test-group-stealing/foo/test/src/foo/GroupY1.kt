package foo

import org.junit.jupiter.api.Test

class GroupY1 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Hades", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Indra", 12)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Jupiter", 16)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Kali", 13)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Loki", 14)
    }

    @Test
    @Throws(Exception::class)
    fun test6() {
        testGreeting("Mars", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test7() {
        testGreeting("Neptune", 13)
    }
}
