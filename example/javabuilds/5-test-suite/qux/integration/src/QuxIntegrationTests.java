package qux;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class QuxIntegrationTests {

  @Test
  public void helloworld() {
    String result = Qux.hello();
    assertEquals("Hello World", result);
  }
}