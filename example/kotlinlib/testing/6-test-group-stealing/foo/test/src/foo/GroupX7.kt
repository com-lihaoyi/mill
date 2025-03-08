package foo

import org.junit.jupiter.api.Test

class GroupX7 : RandomTestsUtils() {
    @Test
    @Throws(Exception::class)
    fun test1() { testGreeting("Selene", 52) }
    
    @Test
    @Throws(Exception::class)
    fun test2() { testGreeting("Themis", 47) }
} 