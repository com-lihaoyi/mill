package foo

import kotlin.test.Test
import kotlin.test.assertEquals

class FooTests {

  @Test
  fun testSimple() {
    val result = Foo.generateHtml("hello")
    assertEquals("<h1>hello</h1>", result)
  }

  @Test
  fun testEscaping() {
    val result = Foo.generateHtml("<hello>")
    assertEquals("<h1>&lt;hello&gt;</h1>", result)
  }
}