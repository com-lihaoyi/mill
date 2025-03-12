package foo

import org.junit.jupiter.api.Test

class GroupX2 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Chronos", 350)
    }
}
