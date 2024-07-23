package bar;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BarTests {

  @Test
  public void hello() {
    String result = Bar.hello();
    assertTrue(result.startsWith("Hello"));
  }

  @Test
  public void world() {
    String result = Bar.hello();
    assertTrue(result.endsWith("World"));
  }
}