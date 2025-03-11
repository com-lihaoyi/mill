package foo

import org.junit.jupiter.api.Assertions.assertEquals

open class RandomTestsUtils {
    @Throws(Exception::class)
    protected fun testGreeting(name: String, sleepTime: Int) {
        val greeted = Foo.greet(name)
        Thread.sleep(sleepTime.toLong())
        assertEquals("Hello $name", greeted)
    }
}
