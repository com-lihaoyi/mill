package mill.constants;

public class ConfigConstants {
  public static final String millVersion = "mill-version";
  public static final String millJvmVersion = "mill-jvm-version";
  public static final String millJvmIndexVersion = "mill-jvm-index-version";
  public static final String millJvmOpts = "mill-jvm-opts";
  public static final String millOpts = "mill-opts";

  public static String[] all() {
    return new String[] {millVersion, millJvmVersion, millJvmIndexVersion, millJvmOpts, millOpts};
  }
}
