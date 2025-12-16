package foo

import org.junit.jupiter.api.Test

class GroupY1 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Hades", 150)
    }
}
