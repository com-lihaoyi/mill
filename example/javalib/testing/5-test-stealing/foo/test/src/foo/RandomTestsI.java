package foo;

import org.junit.Test;

public class RandomTestsI extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Mars", 16);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("North", 12);
  }

  @Test
  public void test3() throws Exception {
    testGreeting("Onyx", 14);
  }

  @Test
  public void test4() throws Exception {
    testGreeting("Phoenix", 15);
  }

  @Test
  public void test5() throws Exception {
    testGreeting("Quest", 13);
  }

  @Test
  public void test6() throws Exception {
    testGreeting("Rain", 11);
  }

  @Test
  public void test7() throws Exception {
    testGreeting("Sky", 17);
  }
}
