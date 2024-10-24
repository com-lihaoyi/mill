package foo.bar;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class Bar {
  public static void main(String text){
    Context context = new Context();
    context.setVariable("text", text);
    String value = new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
    System.out.println("Bar.value: " + value);
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Bar").build();
    parser.addArgument("--text").required(true);

    Namespace res = parser.parseArgs(args);
    main(res.getString("text"));
  }
}
