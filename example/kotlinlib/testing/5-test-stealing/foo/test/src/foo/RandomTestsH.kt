package foo

import org.junit.jupiter.api.Test

class RandomTestsH : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Haven", 22) }
} 