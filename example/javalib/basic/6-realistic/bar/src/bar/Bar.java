package bar;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Bar {
  public static String value() {
    Context context = new Context();
    context.setVariable("text", "world");
    return new TemplateEngine().process("<p th:text=\"${text}\"></p>", context);
  }
}
