package foo

import org.junit.jupiter.api.Test

class GroupY7 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Horus", 34)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Isis", 32)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Juno", 33)
    }
}
