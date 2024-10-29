package foo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FooTests {

  @Test
  public void test() {
    assertEquals(Foo.value, "<h1>hello</h1>");
  }
}