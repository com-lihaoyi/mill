package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooIntegrationTests {

  @Test
  public void hello() {
    String result = Foo.hello();
    assertEquals("Hello World", result);
  }
}
