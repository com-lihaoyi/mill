package foo

import org.junit.jupiter.api.Test

class GroupY5 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Xiuhtecuhtli", 26)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Yarilo", 22)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Zephyrus", 23)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Aegir", 25)
    }
} 
