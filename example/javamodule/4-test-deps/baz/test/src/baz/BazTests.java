package baz;

import org.junit.Test;
import com.google.common.math.IntMath;

import static org.junit.Assert.*;

public class BazTests {

  @Test
  public void simple() {
    BazTestUtils.bazAssertEquals(Baz.getValue(), IntMath.mean(122, 124));
  }
}