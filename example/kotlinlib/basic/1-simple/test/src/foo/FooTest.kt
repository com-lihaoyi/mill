package foo

import com.google.common.html.HtmlEscapers.htmlEscaper
import foo.generateHtml
import org.junit.Assert.assertEquals
import org.junit.Test

class FooTest {
    @Test
    fun testSimple() {
        assertEquals(generateHtml("hello"), "<h1>hello</h1>")
    }

    @Test
    fun testEscaping() {
        assertEquals(generateHtml("<hello>"), "<h1>" + htmlEscaper().escape("<hello>") + "</h1>")
    }
}
