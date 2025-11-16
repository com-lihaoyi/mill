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
    String out = RED;
    int m = 0;
    int n = 10;
    for(int i = 0; i < n; i += 1) {
      m += 1;
      out += m + "\n";
    }
    out += GREEN;
    for(int i = 0; i < n; i += 1) {
      m += 1;
      out += m + "\n";
    }
    out += BLUE;
    for(int i = 0; i < n; i += 1) {
      m += 1;
      out += m + "\n";
    }
    out += CYAN;
    for(int i = 0; i < n; i += 1) {
      m += 1;
      out += m + "\n";
    }
    out += MAGENTA;
    for(int i = 0; i < n; i += 1) {
      m += 1;
      out += m + "\n";
    }
    out += YELLOW;
    for(int i = 0; i < n; i += 1) {
      m += 1;
      out += m + "\n";
    }
    out += RESET;
    System.out.println(out);
  }

}
