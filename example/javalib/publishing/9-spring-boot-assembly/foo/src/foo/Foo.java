package foo;

public class Foo {
  public static String value = "<h1>hello</h1>";

  public static void main(String[] args) {
    System.out.println("Foo.value: " + Foo.value);
    System.out.println("Bar.value: " + bar.Bar.value());
    System.out.println("Qux.value: " + qux.Qux.value);
  }
}
