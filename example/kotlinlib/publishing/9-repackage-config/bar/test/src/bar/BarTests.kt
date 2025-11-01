package bar

import org.junit.Assert.assertEquals
import org.junit.Test

class BarTests {

    @Test
    fun test() {
        assertEquals(Bar.value(), "<p>world</p>")
    }
}
