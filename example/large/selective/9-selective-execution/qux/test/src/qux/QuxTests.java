package qux;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QuxTests {

  @Test
  public void simple() {
    String result = Qux.generateHtml("world");
    assertEquals("<p>world!</p>", result);
  }
}
