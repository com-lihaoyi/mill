package foo;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FooTests {

  @Test
  public void hello() {
    String result = Foo.hello();
    assertTrue(result.startsWith("Hello"));
  }

  @Test
  public void world() {
    String result = Foo.hello();
    assertTrue(result.endsWith("World"));
  }
}