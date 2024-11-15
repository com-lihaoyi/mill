package foo

import bar.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class HelloKotlinTestPackageTests {
    @Test
    fun success() {
        assertEquals(getString(), "Hello, world")
    }

    @Test
    fun failure() {
        assertEquals(getString(), "Not hello, world")
    }
}
