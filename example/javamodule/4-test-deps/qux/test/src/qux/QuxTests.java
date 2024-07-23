package qux;

import org.junit.Test;

public class QuxTests {

  @Test
  public void simple() {
    baz.BazTestUtils.bazAssertEquals("xyz", Qux.getValue());
  }
}