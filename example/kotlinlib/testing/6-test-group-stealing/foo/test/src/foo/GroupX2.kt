package foo

import org.junit.jupiter.api.Test

class GroupX2 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Chronos", 35) }
    
    @Test
    @Throws(Exception::class)
    fun test2() { testGreeting("Demeter", 31) }
    
    @Test
    @Throws(Exception::class)
    fun test3() { testGreeting("Eos", 32) }
} 