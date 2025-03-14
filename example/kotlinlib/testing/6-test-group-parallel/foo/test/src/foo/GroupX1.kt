package foo

import org.junit.jupiter.api.Test

class GroupX1 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Aether", 550)
    }
}
