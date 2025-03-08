package foo

import org.junit.jupiter.api.Test

class GroupX4 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Janus", 21) }
} 