package foo

import org.junit.jupiter.api.Test

class RandomTestsI : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Mars", 16)
    }
}
