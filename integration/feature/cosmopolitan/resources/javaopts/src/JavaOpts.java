package javaopts;

public class JavaOpts {
  public static void main(String[] args) {
    System.getProperties().forEach((k, v) -> System.out.printf("%s=%s\n", k, v));
  }
}
