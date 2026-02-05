package com.example.foo.blackbox;

import com.example.foo.Foo;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Blackbox test - tests public API only as an external consumer would.
 * This test HAS a module-info.java, so it runs on the module path
 * and can only access exported packages.
 */
public class FooBlackboxTest {
  
  @Test
  public void testGreet() {
    Foo foo = new Foo();
    assertEquals("Hello, World!", foo.greet("World"));
  }
}
