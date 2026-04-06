package foo;

import java.io.IOException;
import java.io.InputStream;

public class Foo {
  public static void main(String[] args) throws IOException {
    InputStream inputStream = Foo.class.getClassLoader().getResourceAsStream("application.conf");
    String conf = new String(inputStream.readAllBytes());
    System.out.println("Loaded application.conf from resources: " + conf);
    System.out.println("Loaded test.property: " + System.getProperty("test.property"));
  }
}
