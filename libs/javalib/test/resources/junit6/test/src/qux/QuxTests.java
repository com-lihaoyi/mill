package qux;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class QuxTests {

  @Test
  public void hello() {
    assertTrue(true);
  }

}

// this class should not be detected as a test
class Dummy{}
