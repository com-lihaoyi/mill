package foo;

import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

public class HelloTests {

  @Test
  public void hello() throws Exception {
    System.out.println("Testing Hello");
    Thread.sleep(1000);
    System.out.println("Testing Hello Completed");
  }
}
