package foo;

import org.junit.Test;

public class RandomTestsH extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Haven", 22);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Iris", 18);
  }

  @Test
  public void test3() throws Exception {
    testGreeting("Jazz", 20);
  }

  @Test
  public void test4() throws Exception {
    testGreeting("Kit", 15);
  }

  @Test
  public void test5() throws Exception {
    testGreeting("Lake", 21);
  }
}
