package bar;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BarTests {

  @Test
  public void test() {
    assertEquals(Bar.value(), "<p>world</p>");
  }
}