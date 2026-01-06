package qux;

import org.junit.jupiter.api.Test;

public class QuxIntegrationTests {

  @Test
  public void helloworld() {
    String result = Qux.hello();
    QuxTests.assertHello(result);
    QuxTests.assertWorld(result);
  }
}
