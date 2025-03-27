package foo

import org.junit.jupiter.api.Test

class RandomTestsD : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Kai", 140)
    }
}
