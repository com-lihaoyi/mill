package foo;

public class Foo {
  public static void main(String[] args) {
    System.out.println(greet("World"));
  }

  public static String greet(String name) {
    return "Hello " + name;
  }
}
