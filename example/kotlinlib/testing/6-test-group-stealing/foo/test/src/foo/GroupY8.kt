package foo

import org.junit.jupiter.api.Test

class GroupY8 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Khonsu", 53)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Leto", 46)
    }
} 
