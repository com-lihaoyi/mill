package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooTest2 {
  @Test
  public void testBar() {
    assertEquals(1, Foo.bar());
  }
}
