package foo;

import static org.junit.Assert.assertEquals;

public class RandomTestsUtils {
  protected void testGreeting(String name, int sleepTime) throws Exception {
    String greeted = Foo.greet(name);
    Thread.sleep(sleepTime);
    assertEquals("Hello " + name, greeted);
  }
}
