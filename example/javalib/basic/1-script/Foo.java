//| jvmId: 11.0.28
//| mvnDeps:
//| - net.sourceforge.argparse4j:argparse4j:0.9.0
//| - org.thymeleaf:thymeleaf:3.1.1.RELEASE
//| - org.slf4j:slf4j-nop:2.0.7

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class Foo {
  public static String generateHtml(String text) {
    Context context = new Context();
    context.setVariable("text", text);
    return new TemplateEngine().process("<h1 th:text=\"${text}\"></h1>", context);
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("template")
        .build()
        .defaultHelp(true)
        .description("Inserts text into a HTML template");

    parser.addArgument("-t", "--text").required(true).help("text to insert");

    Namespace ns = null;
    ns = parser.parseArgs(args);
    System.out.println("Jvm Version: " + System.getProperty("java.version"));
    System.out.println(generateHtml(ns.getString("text")));
  }
}
