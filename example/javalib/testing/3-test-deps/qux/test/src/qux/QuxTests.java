package qux;

import com.google.common.base.Ascii;
import org.junit.Test;

public class QuxTests {

  @Test
  public void simple() {
    baz.BazTestUtils.bazAssertEquals(Ascii.toLowerCase("XYZ"), Qux.getValue());
  }
}
