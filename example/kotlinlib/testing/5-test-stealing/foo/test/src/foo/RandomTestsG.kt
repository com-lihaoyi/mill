package foo

import org.junit.jupiter.api.Test

class RandomTestsG : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Finn", 45)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Gray", 52)
    }
} 
