package foo

import org.junit.jupiter.api.Test

class GroupY4 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Wotan", 950)
    }
}
