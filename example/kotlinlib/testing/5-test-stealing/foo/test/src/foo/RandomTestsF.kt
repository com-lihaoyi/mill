package foo

import org.junit.jupiter.api.Test

class RandomTestsF : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Winter", 12) }
} 