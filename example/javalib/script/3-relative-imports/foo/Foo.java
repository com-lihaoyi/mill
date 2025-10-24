//| moduleDeps: [bar/Bar.java]
//| mvnDeps:
//| - net.sourceforge.argparse4j:argparse4j:0.9.0

package foo;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class Foo {
  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Foo").build();
    parser.addArgument("--text").required(true);
    Namespace res = parser.parseArgs(args);

    String text = res.getString("text");
    System.out.println(bar.Bar.generateHtml(text));
  }
}
