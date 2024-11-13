package qux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class QuxIntegrationTests {

  @Test
  public void helloworld() {
    String result = Qux.hello();
    assertEquals("Hello World", result);
  }
}
