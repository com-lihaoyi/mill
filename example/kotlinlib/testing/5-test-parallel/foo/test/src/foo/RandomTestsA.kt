package foo

import org.junit.jupiter.api.Test

class RandomTestsA : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Storm", 380)
    }
}
