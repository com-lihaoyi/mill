package foo

import org.junit.jupiter.api.Test

class GroupX10 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Echo", 52)
    }
}
