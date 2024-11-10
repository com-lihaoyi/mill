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

  public static void main(String[] args) throws Exception {
    String lineCount = getLineCount();
    System.out.println("Line Count: " + lineCount);
  }
}
