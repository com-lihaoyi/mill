//| moduleDeps: [bar/Bar.java]
package foo;

public class Foo {
  public static void main(String[] args) throws Exception {
    System.out.println(bar.Bar.generateHtml(args[0]));
  }
}
