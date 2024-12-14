package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooTests {
  @Test
  public void simple() {
    assertEquals(Foo.VALUE, "hello");
  }
}
