package foo;

public class Foo {
  public static String getLineCount() {
    try {
      return new String(
          Foo.class.getClassLoader().getResourceAsStream("line-count.txt").readAllBytes());
    } catch (java.io.IOException e) {
      return null;
    }
  }

  static String lineCount = getLineCount();

  public static void main(String[] args) throws Exception {
    System.out.println("Line Count: " + lineCount);
  }
}
