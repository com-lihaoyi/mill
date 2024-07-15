package foo;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class Foo {
  public static void main(String[] args) throws Exception {
    InputStream inputStream = Foo.class.getClassLoader().getResourceAsStream("line-count.txt");
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

    String lineCount = reader.lines().collect(Collectors.joining(System.lineSeparator()));
    System.out.println("Line Count: " + lineCount);
  }
}
