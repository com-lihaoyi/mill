package foo

import org.junit.jupiter.api.Test

class GroupY3 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Ra", 210)
    }
}
