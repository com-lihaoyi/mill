package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooTest1 {
  @Test
  public void testQux() {
    assertEquals(100, Foo.qux());
  }
}
