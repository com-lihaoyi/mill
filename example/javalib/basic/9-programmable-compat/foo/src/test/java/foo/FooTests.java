package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooTests {

  @Test
  public void hello() {
    String result = Foo.hello();
    assertEquals("Hello World", result);
  }
}
