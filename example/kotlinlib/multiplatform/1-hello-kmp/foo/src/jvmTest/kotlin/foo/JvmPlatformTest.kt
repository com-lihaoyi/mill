package foo

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmPlatformTest {
    @Test
    fun platformIsJvm() {
        assertEquals("JVM", platformName())
    }
}
