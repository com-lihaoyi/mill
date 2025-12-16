package foo

import org.junit.jupiter.api.Test

class GroupX3 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Fortuna", 250)
    }
}
