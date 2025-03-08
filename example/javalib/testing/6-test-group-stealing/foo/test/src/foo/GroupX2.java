package foo;

import org.junit.Test;

public class GroupX2 extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Chronos", 35);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Demeter", 31);
  }

  @Test
  public void test3() throws Exception {
    testGreeting("Eos", 32);
  }
}
