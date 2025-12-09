package qux;

import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ FilteredTests.class })
public class FooTests {

  @Test
  public void hello() {
    assertTrue(true);
  }

}
