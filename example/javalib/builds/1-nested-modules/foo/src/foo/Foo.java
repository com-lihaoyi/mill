package foo;

import org.apache.commons.text.StringEscapeUtils;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import foo.qux.Qux;

public class Foo {

  public static void main(String barText, String quxText, String fooText) {
    Qux.main(barText, quxText);

    String value = "<p>" + StringEscapeUtils.escapeHtml4(fooText) + "</p>";
    System.out.println("Foo.value: " + value);
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Foo").build();
    parser.addArgument("--bar-text").required(true);
    parser.addArgument("--qux-text").required(true);
    parser.addArgument("--foo-text").required(true);

    Namespace res = parser.parseArgs(args);
    main(res.getString("bar_text"), res.getString("qux_text"), res.getString("foo_text"));
  }
}