package bar;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Bar {
  public static String generateHtml(String text) {
    Context context = new Context();
    context.setVariable("text", text);
    return new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
  }
}
