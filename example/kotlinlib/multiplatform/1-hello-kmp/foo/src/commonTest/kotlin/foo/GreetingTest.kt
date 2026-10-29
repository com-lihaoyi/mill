package foo

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greetingMentionsPlatform() {
        assertTrue(greeting().startsWith("Hello from "))
        assertTrue(greeting().endsWith(platformName()))
    }
}
