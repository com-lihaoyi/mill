package bar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BarTests {

  @Test
  public void test() {
    assertEquals(Bar.value(), "<p>world</p>");
  }
}
