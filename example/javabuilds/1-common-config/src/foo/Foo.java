package foo;
import org.apache.commons.text.StringEscapeUtils;

public class Foo {
  public static final String value = "<h1>" + StringEscapeUtils.escapeHtml4("hello") + "</h1>";

  public static void main(String[] args) {
    System.out.println("Foo.value: " + Foo.value);
  }
}