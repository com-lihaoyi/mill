package foo;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class Foo {

  public static final String VALUE = "hello";

  public static void mainFunction(String fooText, String barText) {
    System.out.println("Foo.value: " + Foo.VALUE);
    System.out.println("Bar.value: " + bar.Bar.generateHtml(barText));
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Foo").build();
    parser.addArgument("--foo-text").required(true);
    parser.addArgument("--bar-text").required(true);

    Namespace res = parser.parseArgs(args);

    String fooText = res.getString("foo_text");
    String barText = res.getString("bar_text");

    mainFunction(fooText, barText);
  }
}
