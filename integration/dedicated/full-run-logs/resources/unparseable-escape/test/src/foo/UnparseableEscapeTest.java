package foo;

import org.junit.Test;

public class UnparseableEscapeTest {
  @Test
  public void printsUnparseableEscape() {
    // A colour code, followed by an escape sequence that is cut off
    System.out.println("\u001b[31mred\u001b[0m then a truncated escape \u001b[");
    // More than a pipe buffer's worth of output (64 KiB on Linux)
    String line = "x".repeat(999);
    for (int i = 0; i < 80; i++) System.out.println(line);
    System.out.println("after");
  }
}
