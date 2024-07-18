package foo;

import org.apache.commons.text.StringEscapeUtils;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

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
    System.out.println("<h1>" + StringEscapeUtils.escapeHtml4(text) + "</h1>");
  }
}