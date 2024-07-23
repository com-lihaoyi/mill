package baz;

import org.junit.Test;
import static org.junit.Assert.*;

public class BazTests {

  @Test
  public void simple() {
    BazTestUtils.bazAssertEquals(Baz.getValue(), 123);
  }
}