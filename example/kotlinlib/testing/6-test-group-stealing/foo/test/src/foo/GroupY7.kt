package foo

import org.junit.jupiter.api.Test

class GroupY7 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Horus", 34)
    }
}
