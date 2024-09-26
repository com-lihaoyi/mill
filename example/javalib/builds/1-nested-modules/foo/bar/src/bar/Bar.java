package foo.bar;

import org.apache.commons.text.StringEscapeUtils;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class Bar {
  public static void main(String text){
    String value = "<h1>" + StringEscapeUtils.escapeHtml4(text) + "</h1>";
    System.out.println("Bar.value: " + value);
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser = ArgumentParsers.newFor("Bar").build();
    parser.addArgument("--text").required(true);

    Namespace res = parser.parseArgs(args);
    main(res.getString("text"));
  }
}