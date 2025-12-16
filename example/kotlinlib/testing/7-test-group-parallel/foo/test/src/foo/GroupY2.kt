package foo

import org.junit.jupiter.api.Test

class GroupY2 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Odin", 340)
    }
}
