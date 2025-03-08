package foo;

import org.junit.Test;

public class GroupX3 extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Fortuna", 25);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Gaia", 22);
  }

  @Test
  public void test3() throws Exception {
    testGreeting("Helios", 28);
  }

  @Test
  public void test4() throws Exception {
    testGreeting("Iris", 24);
  }
}
