package bar;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Bar {
    public static String generateHtml(String text){
        Context context = new Context();
        context.setVariable("text", text);
        return new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
    }

    public static void main(String[] args) {
        System.out.println("Bar.value: " + generateHtml(args[0]));
    }
}
