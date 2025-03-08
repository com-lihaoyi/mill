package foo

import org.junit.jupiter.api.Test

class GroupY9 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Minerva", 21) }
    
    @Test
    @Throws(Exception::class)
    fun test2() { testGreeting("Nike", 18) }
    
    @Test
    @Throws(Exception::class)
    fun test3() { testGreeting("Osiris", 19) }
    
    @Test
    @Throws(Exception::class)
    fun test4() { testGreeting("Pan", 22) }
    
    @Test
    @Throws(Exception::class)
    fun test5() { testGreeting("Qebui", 17) }
} 