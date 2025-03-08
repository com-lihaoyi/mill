package foo

import org.junit.jupiter.api.Test

class GroupY3 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Ra", 21) }
    
    @Test
    @Throws(Exception::class)
    fun test2() { testGreeting("Saturn", 18) }
    
    @Test
    @Throws(Exception::class)
    fun test3() { testGreeting("Thor", 19) }
    
    @Test
    @Throws(Exception::class)
    fun test4() { testGreeting("Ullr", 22) }
    
    @Test
    @Throws(Exception::class)
    fun test5() { testGreeting("Venus", 17) }
} 