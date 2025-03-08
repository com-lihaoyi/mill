package foo

import org.junit.jupiter.api.Test

class GroupX6 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Perseus", 34)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Quirinus", 32)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Rhea", 33)
    }
} 
