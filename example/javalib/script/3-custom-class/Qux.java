//| extends: [millbuild.LineCountJavaModule]
package qux;

public class Qux {
  public static String getLineCount() throws Exception {
    return new String(
        Qux.class.getClassLoader().getResourceAsStream("line-count.txt").readAllBytes());
  }

  public static void main(String[] args) throws Exception {
    System.out.println("Line Count: " + getLineCount());
  }
}
