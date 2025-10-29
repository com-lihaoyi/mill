package foo;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class Foo {
  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Foo")
        .build()
        .defaultHelp(true)
        .description("Process some integers.");
    parser.addArgument("-t", "--text").required(true).help("input text");

    Namespace res = parser.parseArgs(args);
    String text = res.getString("text");

    System.out.println("text: " + text);
    System.out.println("MyDeps.value: " + MyDeps.value);
    System.out.println("my.line.count: " + System.getProperty("my.line.count"));
  }
}
