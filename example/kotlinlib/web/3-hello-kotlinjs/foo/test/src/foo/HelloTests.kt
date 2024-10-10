package foo

import kotlin.test.Test
import kotlin.test.assertEquals

class HelloTests {

    @Test
    fun testHello() {
      val result = hello()
      assertEquals(result.trim(), "<h1>Hello World Wrong</h1>")
      result
    }
}

