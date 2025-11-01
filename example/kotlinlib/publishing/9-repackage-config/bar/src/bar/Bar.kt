package bar

import org.thymeleaf.TemplateEngine
import org.thymeleaf.context.Context

class Bar {
    companion object {
        @JvmStatic
        fun value(): String {
            val context = Context()
            context.setVariable("text", "world")
            return TemplateEngine().process("<p th:text=\"\${text}\"></p>", context)
        }
    }
}
