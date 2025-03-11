package foo;

import java.io.IOException;
import java.io.InputStream;

public class Foo {

  // Read `file.txt` from classpath
  public static String groovyGeneratedHtml() throws IOException {
    // Get the resource as an InputStream
    try (InputStream inputStream =
        Foo.class.getClassLoader().getResourceAsStream("groovy-generated.html")) {
      return new String(inputStream.readAllBytes());
    }
  }

  public static void main(String[] args) throws IOException {
    String appClasspathResourceText = Foo.groovyGeneratedHtml();
    System.out.println("Contents of groovy-generated.html is " + appClasspathResourceText);
  }
}
