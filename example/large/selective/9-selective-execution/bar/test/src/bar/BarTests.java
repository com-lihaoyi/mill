package bar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BarTests {

  @Test
  public void simple() {
    String result = Bar.generateHtml("hello");
    assertEquals("<h1>hello</h1>", result);
  }
}
