package foo;

import java.io.IOException;
import java.io.InputStream;

public class Foo {

  // Read `file.txt` from classpath
  public static String classpathResourceText() throws IOException {
    // Get the resource as an InputStream
    try (InputStream inputStream = Foo.class.getClassLoader().getResourceAsStream("file.txt")) {
      return new String(inputStream.readAllBytes());
    }
  }
}