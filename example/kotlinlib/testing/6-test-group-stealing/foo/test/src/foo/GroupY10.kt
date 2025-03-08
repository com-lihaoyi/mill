package foo

import org.junit.jupiter.api.Test

class GroupY10 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Rama", 25)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Set", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Thoth", 26)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Ukko", 24)
    }
}
