package foo;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FooTests {

  @Test
  public void testSimple() {
    int expectedLineCount = 12;
    int actualLineCount = Integer.parseInt(Foo.lineCount.trim());
    assertEquals(expectedLineCount, actualLineCount);
  }
}