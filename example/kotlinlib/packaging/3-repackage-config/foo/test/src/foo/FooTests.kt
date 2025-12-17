package foo

import org.junit.Assert.assertEquals
import org.junit.Test

class FooTests {

    @Test
    fun test() {
        assertEquals(Foo.value, "<h1>hello</h1>")
    }
}
