package foo.qux;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import foo.bar.Bar;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Qux {
  public static void main(String barText, String quxText){
    Bar.main(barText);

    Context context = new Context();
    context.setVariable("text", "world");
    String value = new TemplateEngine().process("<p th:text=\"${text}\"></p>", context);

    System.out.println("Qux.value: " + value);
  }
  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Qux").build();
    parser.addArgument("--bar-text").required(true);
    parser.addArgument("--qux-text").required(true);

    Namespace res = parser.parseArgs(args);

    main(res.getString("bar_text"), res.getString("qux_text"));
  }
}
