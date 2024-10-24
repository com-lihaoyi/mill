package qux;

import org.junit.Test;
import com.google.common.base.Ascii;

public class QuxTests {

  @Test
  public void simple() {
    baz.BazTestUtils.bazAssertEquals(Ascii.toLowerCase("XYZ"), Qux.getValue());
  }
}