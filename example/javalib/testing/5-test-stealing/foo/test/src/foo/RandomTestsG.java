package foo;

import org.junit.Test;

public class RandomTestsG extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Finn", 45);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Gray", 52);
  }
}
