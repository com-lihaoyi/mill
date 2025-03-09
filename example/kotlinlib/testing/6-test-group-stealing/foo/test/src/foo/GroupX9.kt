package foo

import org.junit.jupiter.api.Test

class GroupX9 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Ymir", 17)
    }
} 
