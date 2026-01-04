package baz;

import static org.junit.Assert.*;

import com.google.common.math.IntMath;
import org.junit.Test;

public class BazTests {

  @Test
  public void simple() {
    BazTestUtils.bazAssertEquals(Baz.getValue(), IntMath.mean(122, 124));
  }
}
