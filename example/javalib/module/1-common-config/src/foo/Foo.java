package foo;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Foo {
  public static String value() {
    Context context = new Context();
    context.setVariable("text", "hello");
    return new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
  }

  public static void main(String[] args) {
    System.out.println("Foo.value: " + Foo.value());
  }
}
