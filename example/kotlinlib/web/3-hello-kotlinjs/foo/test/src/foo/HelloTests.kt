package foo

import kotlin.test.Test
import kotlin.test.assertEquals

class HelloTests {

    @Test
    fun failure() {
        assertEquals(getString(), "Not hello, world")
    }
}

