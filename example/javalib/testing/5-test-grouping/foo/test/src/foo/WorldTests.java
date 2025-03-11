package foo;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorldTests {
  @Test
  public void world() throws Exception {
    System.out.println("Testing World");
    String result = new Foo().hello();
    assertTrue(result.endsWith("World"));
    Thread.sleep(1000);
    System.out.println("Testing World Completed");
  }
}
