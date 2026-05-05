package foo

import org.junit.jupiter.api.Test

class RandomTestsC : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Jordan", 950)
    }
}
