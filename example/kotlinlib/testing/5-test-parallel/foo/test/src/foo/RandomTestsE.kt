package foo

import org.junit.jupiter.api.Test

class RandomTestsE : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Sage", 280)
    }
}
