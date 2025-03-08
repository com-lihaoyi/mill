package foo;

import org.junit.Test;

public class GroupY2 extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Odin", 34);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Pluto", 32);
  }

  @Test
  public void test3() throws Exception {
    testGreeting("Quetzal", 33);
  }
}
