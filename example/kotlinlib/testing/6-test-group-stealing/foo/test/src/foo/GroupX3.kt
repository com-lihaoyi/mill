package foo

import org.junit.jupiter.api.Test

class GroupX3 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Fortuna", 25)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Gaia", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Helios", 28)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Iris", 24)
    }
} 
