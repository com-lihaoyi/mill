package mill.daemon;

public class VersionCheck {
  public static void check() {
    var javaVersion = System.getProperty("java.version");
    switch (javaVersion.split("\\.")[0]) {
      case "1":
      case "2":
      case "3":
      case "4":
      case "5":
      case "6":
      case "7":
      case "8":
      case "9":
      case "10":
      case "11":
      case "12":
      case "13":
      case "14":
      case "15":
      case "16":
        System.err.println(
          "Invalid java.version " + javaVersion + ". Mill requires Java 17 and above to run the " +
            "build tool itself. Individual `JavaModule` can be set to lower Java versions via" +
            " `def jvmId = \"11\"`"
        );
        System.exit(1);
    }
  }
}
