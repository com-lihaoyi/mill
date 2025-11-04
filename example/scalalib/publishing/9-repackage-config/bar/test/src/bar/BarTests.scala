package bar

import org.junit.Assert.assertEquals

import org.junit.Test

class BarTests {

  @Test
  def test() = {
    assertEquals(Bar.value, "<p>world</p>")
  }

}
