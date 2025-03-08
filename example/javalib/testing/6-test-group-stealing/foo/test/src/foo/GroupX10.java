package foo;

import org.junit.Test;

public class GroupX10 extends RandomTestsUtils {
  @Test
  public void test1() throws Exception {
    testGreeting("Echo", 52);
  }

  @Test
  public void test2() throws Exception {
    testGreeting("Faunus", 47);
  }
}
