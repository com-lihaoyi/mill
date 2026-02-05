package com.example.foo;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Whitebox test - tests internal implementation details.
 * This test does NOT have a module-info.java, so it runs on the classpath
 * with access to package-private members.
 */
public class FooWhiteboxTest {
  
  @Test
  public void testGreet() {
    Foo foo = new Foo();
    assertEquals("Hello, World!", foo.greet("World"));
  }
}
