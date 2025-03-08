package foo;

import org.junit.Test;

public class RandomTestsE extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Sage", 28);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Talia", 22);
  }

  @Test
  public void test3() throws Exception {
    testGreeting("Urban", 25);
  }

  @Test
  public void test4() throws Exception {
    testGreeting("Violet", 20);
  }
}
