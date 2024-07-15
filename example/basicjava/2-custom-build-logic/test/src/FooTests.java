package foo;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class FooTests {

  @Test
  public void testSimple() {
    // Assuming that lineCount should be an integer in string format.
    int expectedLineCount = 12; // Adjust this to the actual expected value if needed.
    int actualLineCount = Integer.parseInt(Foo.lineCount.trim());
    assertEquals(expectedLineCount, actualLineCount);
  }
}