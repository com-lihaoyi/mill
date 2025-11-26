package mill.constants;

public class ConfigConstants {
  final public static String millVersion = "mill-version";
  final public static String millJvmVersion = "mill-jvm-version";
  final public static String millJvmIndexVersion = "mill-jvm-index-version";
  final public static String millJvmOpts = "mill-jvm-opts";
  final public static String millOpts = "mill-opts";

  public static String[] all(){
    return new String[]{millVersion, millJvmVersion, millJvmIndexVersion, millJvmOpts, millOpts};
  }
}
