package foo

import org.junit.jupiter.api.Test

class RandomTestsG : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Finn", 45)
    }
} 
