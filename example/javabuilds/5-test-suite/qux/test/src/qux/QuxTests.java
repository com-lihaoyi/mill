package qux;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class QuxTests {

  @Test
  public void hello() {
    String result = Qux.hello();
    assertTrue(result.startsWith("Hello"));
  }

  @Test
  public void world() {
    String result = Qux.hello();
    assertTrue(result.endsWith("World"));
  }
}