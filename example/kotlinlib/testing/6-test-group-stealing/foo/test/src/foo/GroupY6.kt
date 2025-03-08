package foo

import org.junit.jupiter.api.Test

class GroupY6 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() {
        testGreeting("Baldur", 17)
    }

    @Test
    @Throws(Exception::class)
    fun test2() {
        testGreeting("Calypso", 15)
    }

    @Test
    @Throws(Exception::class)
    fun test3() {
        testGreeting("Dagon", 18)
    }

    @Test
    @Throws(Exception::class)
    fun test4() {
        testGreeting("Enki", 16)
    }

    @Test
    @Throws(Exception::class)
    fun test5() {
        testGreeting("Freya", 17)
    }

    @Test
    @Throws(Exception::class)
    fun test6() {
        testGreeting("Geb", 15)
    }
} 
