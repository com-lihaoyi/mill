package foo

import org.junit.jupiter.api.Test

class GroupY6 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Baldur", 17)
    }
}
