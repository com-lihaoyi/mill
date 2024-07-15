package bar;

import org.apache.commons.text.StringEscapeUtils;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class Bar {
    public static String generateHtml(String text) {
        String value = "<h1>" + StringEscapeUtils.escapeHtml4(text) + "</h1>";
        return value;
    }

    public static void main(String[] args) {
        System.out.println("Bar.value: " + generateHtml(args[0]));
    }
}