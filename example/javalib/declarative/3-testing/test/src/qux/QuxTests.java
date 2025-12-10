package qux;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class QuxTests {

  public static void assertHello(String result) {
    assertTrue(result.startsWith("Hello"));
  }

  public static void assertWorld(String result) {
    assertTrue(result.endsWith("World"));
  }

  @Test
  public void hello() {
    String result = Qux.hello();
    assertHello(result);
  }

  @Test
  public void world() {
    String result = Qux.hello();
    assertWorld(result);
  }
}
