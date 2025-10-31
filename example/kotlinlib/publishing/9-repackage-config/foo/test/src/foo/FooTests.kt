package foo

import org.junit.Assert.assertEquals

import org.junit.Test

public class FooTests {

  @Test
  public void test() {
    assertEquals(Foo.value, "<h1>hello</h1>")
  }
}
