package foo;

import org.junit.Test;


public class FooTest {
  @Test
  public void testSimple() {
    final String RED = "\u001b[31m";
    final String GREEN = "\u001B[32m";
    final String BLUE = "\u001b[34m";
    final String CYAN = "\u001b[36m";
    final String MAGENTA = "\u001b[35m";
    final String YELLOW = "\u001b[33m";
    final String RESET = "\u001B[0m";
    final String[] colors = {RED, GREEN, BLUE, CYAN, MAGENTA, YELLOW};
    String out = "";
    int m = 0;
    int n = 10;
    String prefix = "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    for(String color : colors){
      out += color;
      for(int i = 0; i < n; i += 1) {
        m += 1;
        out += prefix + m + "\n";
      }
    }

    out += RESET;
    System.out.println(out);
  }

}
