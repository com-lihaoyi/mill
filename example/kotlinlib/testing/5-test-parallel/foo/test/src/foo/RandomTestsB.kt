package foo

import org.junit.jupiter.api.Test

class RandomTestsB : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Dakota", 180)
    }
}
