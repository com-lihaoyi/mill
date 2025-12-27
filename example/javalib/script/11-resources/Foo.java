//| resources: ["./resources"]
package foo;

public class Foo {
  public static void main(String[] args) throws Exception {
    try (var inputStream = Foo.class.getClassLoader().getResourceAsStream("file.txt")) {
      System.out.println(new String(inputStream.readAllBytes()));
    }
  }
}
