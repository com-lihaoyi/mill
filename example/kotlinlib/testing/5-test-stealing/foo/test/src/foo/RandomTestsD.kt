package foo

import org.junit.jupiter.api.Test

class RandomTestsD : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Kai", 14) }
    
    @Test
    @Throws(Exception::class)
    fun test2() { testGreeting("Luna", 11) }
    
    @Test
    @Throws(Exception::class)
    fun test3() { testGreeting("Mason", 15) }
    
    @Test
    @Throws(Exception::class)
    fun test4() { testGreeting("Nova", 10) }
    
    @Test
    @Throws(Exception::class)
    fun test5() { testGreeting("Owen", 13) }
    
    @Test
    @Throws(Exception::class)
    fun test6() { testGreeting("Piper", 12) }
    
    @Test
    @Throws(Exception::class)
    fun test7() { testGreeting("Quinn", 14) }
    
    @Test
    @Throws(Exception::class)
    fun test8() { testGreeting("River", 11) }
} 