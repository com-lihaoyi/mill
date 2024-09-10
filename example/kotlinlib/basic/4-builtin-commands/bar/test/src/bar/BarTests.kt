package bar

import bar.generateHtml
import org.junit.Assert.assertEquals
import org.junit.Test

public class BarTests {

    @Test
    fun testSimple() {
        val result = generateHtml("hello")
        assertEquals("<h1>hello</h1>", result)
    }

    @Test
    fun testEscaping() {
        val result = generateHtml("<hello>")
        assertEquals("<h1>&lt;hello&gt;</h1>", result)
    }
}