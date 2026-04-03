package ultra;

public class Main {

  public static String generateHtml(String text) {
    return "<h1>" + text + "</h1>";
  }

  // Entry point
  public static void main(String[] args) {
    if (args.length < 1) {
      System.err.println("Usage: java ultra.Main <text>");
      System.exit(1);
    }

    String text = args[0];
    System.out.println(generateHtml(text));
  }
}
