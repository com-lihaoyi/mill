package foo;

import org.apache.commons.text.StringEscapeUtils;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;


public class Foo{
    public static String generateHtml(String text){
        return "<h1>" + StringEscapeUtils.escapeHtml4(text) + "</h1>";
    }
    public static void main(String[] args) {
        ArgumentParser parser = ArgumentParsers.newFor("template").build()
                .defaultHelp(true)
                .description("Inserts text into a HTML template");

        parser.addArgument("-t", "--text")
                .required(true)
                .help("text to insert");

        Namespace ns = null;
        try {
            ns = parser.parseArgs(args);
        }catch(Exception e){
            System.out.println(e.getMessage());
            System.exit(1);
        }

        System.out.println(generateHtml(ns.getString("text")));
    }
}
