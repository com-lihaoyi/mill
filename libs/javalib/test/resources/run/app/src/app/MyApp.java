package app;

import lib.Thing;

public class MyApp {
  public static void main(String[] args) throws java.net.URISyntaxException {
    String appUri = MyApp.class.getProtectionDomain().getCodeSource().getLocation().toURI().toASCIIString();
    String libUri = Thing.class.getProtectionDomain().getCodeSource().getLocation().toURI().toASCIIString();
    System.out.println("App URI: " + appUri);
    System.out.println("Lib URI: " + libUri);
  }
}
