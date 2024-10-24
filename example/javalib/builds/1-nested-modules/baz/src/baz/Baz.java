package baz;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import foo.Foo;

public class Baz {

  public static void main(String barText, String quxText, String fooText, String bazText) {
    Foo.main(barText, quxText, fooText);
    Context context = new Context();
    context.setVariable("text", bazText);
    String value = new TemplateEngine().process("<p th:text=\"${text}\"></p>", context);
    System.out.println("Baz.value: " + value);
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Baz").build();
    parser.addArgument("--bar-text").required(true);
    parser.addArgument("--qux-text").required(true);
    parser.addArgument("--foo-text").required(true);
    parser.addArgument("--baz-text").required(true);

    Namespace res = parser.parseArgs(args);
    main(res.getString("bar_text"), res.getString("qux_text"), res.getString("foo_text"), res.getString("baz_text"));
  }
}
