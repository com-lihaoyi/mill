package foo;

public class Foo {
  public static void main(String[] args) {
    System.out.println(new Foo().hello());
  }

  public String hello() {
    return "Hello World";
  }
}