package qux;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
