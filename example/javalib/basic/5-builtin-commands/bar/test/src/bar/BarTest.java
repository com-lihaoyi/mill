package bar;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BarTests {

  @Test
  public void testSimple() {
    String result = Bar.generateHtml("hello");
    assertEquals("<h1>hello</h1>", result);
  }

  @Test
  public void testEscaping() {
    String result = Bar.generateHtml("<hello>");
    assertEquals("<h1>&lt;hello&gt;</h1>", result);
  }
}
