package foo

import org.junit.jupiter.api.Test

class GroupX9 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Ymir", 17)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Zeus", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Atlas", 18)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Balder", 16)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Ceres", 17)
    }

    @Test
    @Throws(Exception::class)
    fun test6() {
        testGreeting("Diana", 15)
    }
}
