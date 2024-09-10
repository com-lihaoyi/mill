package bar

import bar.generateHtml
import org.junit.Assert.assertEquals
import org.junit.Test

class BarTests {

    @Test
    fun simple() {
        val result = generateHtml("hello")
        assertEquals("<h1>hello</h1>", result)
    }

    @Test
    fun escaping() {
        val result = generateHtml("<hello>")
        assertEquals("<h1>&lt;hello&gt;</h1>", result)
    }
}
