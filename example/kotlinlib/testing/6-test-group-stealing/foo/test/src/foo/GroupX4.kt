package foo

import org.junit.jupiter.api.Test

class GroupX4 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Janus", 21)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Kratos", 18)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Luna", 19)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Maia", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Nyx", 17)
    }
}
