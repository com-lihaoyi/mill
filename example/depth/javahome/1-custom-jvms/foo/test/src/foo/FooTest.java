package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooTest {
  @Test
  public void testSimple() {
    System.out.println("Testing with JVM version: " + System.getProperty("java.version"));
    assertEquals(System.getProperty("java.version"), "11.0.20");
  }
}
