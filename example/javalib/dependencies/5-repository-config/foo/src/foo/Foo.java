package foo;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Foo {

  public static void main(String[] args) throws Exception{
    ArgumentParser parser = ArgumentParsers.newFor("Foo").build()
            .defaultHelp(true);

    parser.addArgument("--text");

    Namespace res = parser.parseArgs(args);
    String text = res.getString("text");
    main(text);
  }

  public static void main(String text) {
    Context context = new Context();
    context.setVariable("text", text);
    System.out.println(new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context));
  }
}
