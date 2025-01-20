package foo;

import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

public class WorldTests {
  @Test
  public void world() throws Exception {
    System.out.println("Testing World");
    Thread.sleep(1000);
    System.out.println("Testing World Completed");
  }
}
