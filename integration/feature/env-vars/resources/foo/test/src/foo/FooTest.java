package foo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FooTest {
  @Test
  public void testSimple() {
    String myEnvVar = System.getenv("MY_ENV_VAR");
    System.out.println("test: " + myEnvVar);
    assertEquals(myEnvVar, "abcde");
  }
}
