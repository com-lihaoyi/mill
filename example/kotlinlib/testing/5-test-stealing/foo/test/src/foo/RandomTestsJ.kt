package foo

import org.junit.jupiter.api.Test

class RandomTestsJ : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Storm", 38)
    }
} 
