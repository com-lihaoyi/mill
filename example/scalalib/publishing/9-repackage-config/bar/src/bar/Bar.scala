package bar

import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context

object Bar {
  def value: String = {
    val context = Context()
    context.setVariable("text", "world")
    TemplateEngine().process("<p th:text=\"${text}\"></p>", context)
  }
}
