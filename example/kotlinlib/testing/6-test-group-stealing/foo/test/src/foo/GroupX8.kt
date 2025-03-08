package foo

import org.junit.jupiter.api.Test

class GroupX8 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Uranus", 25)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Vesta", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Woden", 26)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Xipe", 24)
    }
} 
