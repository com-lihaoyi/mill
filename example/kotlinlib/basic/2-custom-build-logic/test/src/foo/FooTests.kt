package foo

import org.junit.Assert.assertEquals
import org.junit.Test
import foo.getLineCount

class FooTests {

    @Test
    fun testSimple() {
        val expectedLineCount = 12
        val actualLineCount = getLineCount()?.trim().let { Integer.parseInt(it) }
        assertEquals(expectedLineCount, actualLineCount)
    }
}
